#!/usr/bin/env bash
# Usage: deploy.sh [init|deploy|smoke|status|down|backup|restore BACKUP_DIR]
set -euo pipefail
umask 077
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$root"
action=${1:-deploy}
case "$action" in init|deploy|smoke|status|down|backup|restore) ;; *) printf '未知操作：%s\n' "$action" >&2; exit 1 ;; esac
env_file=${OFFERPILOT_ENV_FILE:-$root/.env}
[[ "$env_file" = /* ]] || env_file="$root/$env_file"
fail() { printf '部署失败：%s\n' "$*" >&2; exit 1; }
for tool in python3 curl flock; do command -v "$tool" >/dev/null || fail "缺少 $tool"; done
command -v docker >/dev/null || fail '请先安装 Docker Engine 与 Compose >= 2.24.4'
docker_prefix=()
docker_cmd=(docker)
if [[ $(id -u) != 0 ]]; then
    command -v sudo >/dev/null || fail '需要 sudo Docker 权限'
    docker_prefix=(sudo -n)
    docker_cmd=(sudo -n docker)
fi
"${docker_cmd[@]}" info >/dev/null 2>&1 || fail 'Docker 未启动或 sudo -n docker 不可用'
compose_version=$("${docker_cmd[@]}" compose version --short)
python3 - "$compose_version" <<'PY'
import re, sys
v = re.search(r'(\d+)\.(\d+)\.(\d+)', sys.argv[1])
if not v or tuple(map(int, v.groups())) < (2, 24, 4):
    sys.exit('需要 Docker Compose >= 2.24.4（production 使用 !override / !reset）')
PY
# Lock the selected environment, not a global project or another worktree.
exec 9>"${env_file}.deploy.lock"
flock -n 9 || fail '同一环境正在执行部署/备份/恢复'
if [[ ! -e "$env_file" ]]; then
    python3 - "$root/deploy/.env.demo.example" "$env_file" <<'PY'
import base64, os, secrets, sys
example, dest = sys.argv[1:]
domain = os.environ.get('DOMAIN', 'http://localhost')
public = os.environ.get('PUBLIC_URL', domain if '://' in domain else 'https://' + domain)
origin = public.rstrip('/')
values = {
    'DOMAIN': domain, 'PUBLIC_URL': origin, 'CORS_ORIGINS': origin,
    'MINIO_PUBLIC_ENDPOINT': origin,
    'POSTGRES_PASSWORD': secrets.token_hex(32), 'REDIS_PASSWORD': secrets.token_hex(32),
    'JWT_SECRET': base64.b64encode(secrets.token_bytes(64)).decode(),
    'DATA_ENCRYPTION_KEY': base64.b64encode(secrets.token_bytes(32)).decode(),
    'MINIO_ROOT_USER': 'op' + secrets.token_hex(10), 'MINIO_ROOT_PASSWORD': secrets.token_hex(32),
    'DEMO_ACCOUNT_PASSWORD': 'Demo' + secrets.token_hex(24),
    'TAG': os.environ.get('TAG', 'demo'),
}
# Explicit process variables are copied only on first init. Never print their values.
for line in open(example):
    if '=' in line and not line.startswith('#'):
        key = line.split('=', 1)[0]
        if key in os.environ:
            values[key] = os.environ[key]
with open(dest, 'x', encoding='utf-8') as out:
    for line in open(example):
        key = line.split('=', 1)[0]
        if key in values:
            value = values[key]
            if any(c in value for c in '\n\r\0\''):
                sys.exit('环境变量不能含换行、NUL 或单引号：' + key)
            out.write(key + "='" + value + "'\n")
        else:
            out.write(line)
print('已生成环境文件（权限 600）；现有环境再次运行不会重置密钥。')
PY
fi
chmod 600 "$env_file"
# Read dotenv as data, never execute it as shell code. Output only public routing values.
settings=$(python3 - "$env_file" "$action" <<'PY'
import base64, re, shlex, sys
from urllib.parse import urlsplit
values = {}
for line in open(sys.argv[1]):
    line = line.strip()
    if not line or line.startswith('#'): continue
    key, sep, value = line.partition('=')
    if not sep or not re.fullmatch(r'[A-Z][A-Z0-9_]*', key): sys.exit('非法 dotenv 行')
    parts = shlex.split(value, comments=True)
    values[key] = ' '.join(parts)
if sys.argv[2] != 'init':
    for key in ('POSTGRES_PASSWORD', 'REDIS_PASSWORD', 'MINIO_ROOT_USER', 'MINIO_ROOT_PASSWORD', 'JWT_SECRET', 'DATA_ENCRYPTION_KEY'):
        value = values.get(key, '')
        if not value or 'replace-with' in value or 'change-me' in value: sys.exit('缺少有效配置：' + key)
    if len(values['JWT_SECRET'].encode()) < 64: sys.exit('JWT_SECRET 至少 64 字节')
    try: assert len(base64.b64decode(values['DATA_ENCRYPTION_KEY'], validate=True)) == 32
    except Exception: sys.exit('DATA_ENCRYPTION_KEY 必须为 32 字节 base64')
    if sys.argv[2] in ('deploy', 'smoke'):
        if not values.get('DEEPSEEK_API_KEY') or 'replace-with' in values['DEEPSEEK_API_KEY']:
            sys.exit('请在环境文件配置 DEEPSEEK_API_KEY，再运行 deploy')
        if values.get('DEMO_ACCOUNT_ENABLED') != 'true': sys.exit('生产冒烟需要 DEMO_ACCOUNT_ENABLED=true')
        if values.get('DEMO_SANDBOX_ENABLED', 'true') != 'true': sys.exit('生产部署必须启用访客沙箱')
        for key in ('OCCUPATION_EMBED_ENABLED', 'NOTES_EMBED_ENABLED'):
            if values.get(key) == 'true' and (not values.get('BAILIAN_API_KEY') or values.get('LLM_EMBEDDING_PROVIDER') != 'openai'):
                sys.exit(key + ' 需要 BAILIAN_API_KEY 与 LLM_EMBEDDING_PROVIDER=openai')
project = values.get('COMPOSE_PROJECT_NAME', 'offerpilot-prod')
if not re.fullmatch(r'[a-z0-9][a-z0-9_-]*', project) or project == 'offerpilot': sys.exit('生产须使用独立 COMPOSE_PROJECT_NAME（不能为 offerpilot）')
public = values.get('PUBLIC_URL', '').rstrip('/')
url = urlsplit(public)
if url.scheme not in ('http', 'https') or not url.hostname or url.path or url.query or url.fragment or url.username: sys.exit('PUBLIC_URL 必须为 http(s) origin')
domain = values.get('DOMAIN', '')
site = urlsplit(domain if '://' in domain else 'https://' + domain)
if site.hostname != url.hostname or site.scheme != url.scheme: sys.exit('DOMAIN 与 PUBLIC_URL 的协议和主机须一致')
print(project)
print(public)
PY
)
mapfile -t setting_lines <<< "$settings"
project=${setting_lines[0]}
public_url=${setting_lines[1]}
compose() {
    # Absolute env_file also goes to backend; --env-file alone only controls interpolation.
    "${docker_prefix[@]}" env "OFFERPILOT_ENV_FILE=$env_file" docker compose \
        --project-name "$project" --env-file "$env_file" \
        -f "$root/deploy/docker-compose.yml" -f "$root/deploy/docker-compose.production.yml" \
        --profile production "$@"
}
compose config --quiet
curl_args=(--silent --show-error --fail --connect-timeout 10 --max-time 30)
[[ -z ${SMOKE_RESOLVE:-} ]] || curl_args+=(--resolve "$SMOKE_RESOLVE")
[[ -z ${CURL_CA_FILE:-} ]] || curl_args+=(--cacert "$CURL_CA_FILE")
smoke() {
    curl "${curl_args[@]}" "$public_url/" | python3 -c 'import sys; assert "<html" in sys.stdin.read().lower(), "缺少前端 HTML"'
    curl "${curl_args[@]}" "$public_url/actuator/health" | python3 -c 'import json,sys; assert json.load(sys.stdin)["status"] == "UP"'
    printf '前端与后端经 Caddy 健康检查通过。\n'
    # Keep JWT and model output only in a private temporary directory; never log them.
    smoke_dir=$(mktemp -d)
    trap 'rm -f "$smoke_dir/login.json"; rm -f "$smoke_dir/auth.conf"; rm -f "$smoke_dir/chat.sse"; rmdir "$smoke_dir"' EXIT
    curl "${curl_args[@]}" -X POST "$public_url/api/v1/auth/demo-login" -o "$smoke_dir/login.json"
    python3 - "$smoke_dir" <<'PY'
import json, pathlib, sys
p = pathlib.Path(sys.argv[1])
data = json.loads((p/'login.json').read_text())['data']
token = data['accessToken']
assert all(c.isalnum() or c in '.-_' for c in token)
(p/'auth.conf').write_text('header = "Authorization: Bearer ' + token + '"\n')
PY
    printf '访客登录与认证对象存储写入通过。\n'
    if [[ ${SMOKE_CHAT:-false} == true ]]; then
        curl "${curl_args[@]}" --max-time 240 --no-buffer --config "$smoke_dir/auth.conf" \
            -H 'Content-Type: application/json' -H 'Accept: text/event-stream' \
            -d '{"content":"用一句话介绍如何开始准备后端求职，不要使用工具。","mode":"AUTO","options":{"modelTier":"FLASH"}}' \
            "$public_url/api/v1/agent/chat" -o "$smoke_dir/chat.sse"
        python3 - "$smoke_dir/chat.sse" <<'PY'
import json, sys
s = open(sys.argv[1]).read()
assert 'event: text.delta' in s and 'event: run.completed' in s, 'SSE 缺少正文或结束事件'
assert 'event: error' not in s, 'SSE 返回业务错误'
for block in s.split('\n\n'):
    if 'event: run.completed' in block:
        data = json.loads(next(x[5:].strip() for x in block.splitlines() if x.startswith('data:')))
        assert data.get('status') == 'COMPLETED', 'SSE 未成功完成'
print('访客沙箱登录与真实 FLASH SSE 对话通过。')
PY
    fi
}
case "$action" in
    init) printf '初始化完成；配置域名和模型 key 后运行 deploy.sh。\n' ;;
    deploy)
        compose pull postgres redis minio caddy
        if [[ ${DEPLOY_BUILD:-true} == true ]]; then
            compose build --pull backend frontend jobs-mcp
        else
            compose pull backend frontend jobs-mcp
        fi
        # Compose --wait treats the completed frontend copy job as not running.
        # Run the copy separately, then wait only on the six long-lived services.
        compose up -d postgres redis minio jobs-mcp backend frontend
        compose up -d --wait --wait-timeout "${DEPLOY_WAIT_SECONDS:-300}" postgres redis minio jobs-mcp backend caddy
        smoke
        printf '部署完成：%s（项目 %s）。\n' "$public_url" "$project"
        ;;
    smoke) smoke ;;
    status) compose ps --all ;;
    down) compose down --remove-orphans ;;
    backup)
        backup_dir=${BACKUP_DIR:-$root/backups}/${project}_$(date -u +%Y%m%dT%H%M%S)_$$
        mkdir -p "$backup_dir"
        # Stop every writer before capturing DB and the complete S3 disk (including filer metadata).
        trap 'compose start --wait --wait-timeout "${DEPLOY_WAIT_SECONDS:-300}" minio backend caddy >/dev/null' EXIT
        compose stop caddy backend minio
        compose exec -T postgres sh -ec 'pg_dump -Fc -U "$POSTGRES_USER" "$POSTGRES_DB"' > "$backup_dir/postgres.dump"
        compose run --rm --no-deps -T --entrypoint tar minio -C /data -czf - . > "$backup_dir/s3.tgz"
        cp "$env_file" "$backup_dir/environment.env"
        (cd "$backup_dir" && sha256sum postgres.dump s3.tgz environment.env > SHA256SUMS)
        printf '配套备份完成：%s（含恢复所需密钥，请加密离机保存）。\n' "$backup_dir"
        ;;
    restore)
        [[ -n ${2:-} ]] || fail '用法：deploy.sh restore BACKUP_DIR（仅恢复到新空项目）'
        backup_dir=$(cd "$2" && pwd)
        (cd "$backup_dir" && sha256sum --check --status SHA256SUMS) || fail '备份校验失败'
        # Reject existing project volumes, even when its containers are stopped.
        [[ -z $("${docker_cmd[@]}" volume ls -q --filter "label=com.docker.compose.project=$project") ]] || fail '恢复须使用新项目名和空卷，禁止覆盖现有数据'
        python3 - "$env_file" "$backup_dir/environment.env" <<'PY'
import shlex, sys
def read(p):
    return {k: ' '.join(shlex.split(v, comments=True)) for line in open(p) if not line.startswith('#') and '=' in line for k, v in [line.strip().split('=', 1)]}
a,b = map(read, sys.argv[1:])
for k in ('JWT_SECRET','DATA_ENCRYPTION_KEY','MINIO_ROOT_USER','MINIO_ROOT_PASSWORD','POSTGRES_DB','POSTGRES_USER'):
    assert a.get(k) == b.get(k), '恢复必须保留备份环境配置：' + k
PY
        compose up -d --wait postgres redis
        compose run --rm --no-deps -T --entrypoint tar minio -C /data -xzf - < "$backup_dir/s3.tgz"
        compose exec -T postgres sh -ec 'pg_restore --exit-on-error --no-owner -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < "$backup_dir/postgres.dump"
        printf '数据恢复完成；运行 deploy.sh 启动服务并验收。\n'
        ;;
    *) fail '支持 init / deploy / smoke / status / down / backup / restore' ;;
esac
