"""Offline source-contract checks. Standard library only; does not run Docker or read .env secrets.

This checks the supported Compose source layout, paths and wiring. Full YAML/interpolation validation
still requires `docker compose --env-file .env -f deploy/docker-compose.yml --profile full config --quiet`.
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]


def validate(compose, env, readme, root=ROOT):
    errors = []
    def require(ok, message):
        if not ok:
            errors.append(message)
    blocks = dict(re.findall(r'^  ([a-z][\w-]*):\n(.*?)(?=^  [a-z][\w-]*:|^volumes:|\Z)', compose, re.M | re.S))
    services = ['postgres', 'redis', 'minio', 'jobs-mcp', 'ollama', 'ollama-init', 'backend', 'frontend', 'caddy']
    for name in services:
        require(name in blocks, 'Missing service: ' + name)
    for name in ['postgres', 'redis', 'minio', 'jobs-mcp', 'ollama', 'backend', 'caddy']:
        block = blocks.get(name, '')
        require('healthcheck:' in block and 'test:' in block, 'Missing healthcheck: ' + name)
    backend = blocks.get('backend', '')
    for name in ['postgres', 'redis', 'minio', 'jobs-mcp']:
        require(f'{name}: {{ condition: service_healthy }}' in backend, 'Backend must wait for ' + name)
        for ports in re.findall(r'^    ports: (.+)$', blocks.get(name, ''), re.M):
            require('127.0.0.1:' in ports, 'Data port must bind to loopback: ' + name)
    require('ollama-init: { condition: service_completed_successfully }' in backend, 'Backend must wait for weights')
    for name in ['backend', 'frontend', 'caddy', 'ollama', 'ollama-init']:
        require('profiles: ["full"]' in blocks.get(name, ''), 'Missing full profile: ' + name)
    init = blocks.get('ollama-init', '')
    require('ollama pull bge-m3' in init and 'OLLAMA_HOST: http://ollama:11434' in init, 'Invalid local weights initialization')
    require('ollama: { condition: service_healthy }' in init, 'Weight initialization must wait for Ollama')
    require('ollama_data:/root/.ollama' in blocks.get('ollama', '') and '  ollama_data: {}' in compose, 'Weights must persist')
    require('EMBEDDING_BASE_URL: ${EMBEDDING_BASE_URL:-http://ollama:11434}' in backend, 'Invalid embedding URL')
    require('LLM_MODEL_EMBEDDING: ${LLM_MODEL_EMBEDDING:-bge-m3}' in backend, 'Invalid embedding model')
    for key, value in [('EMBEDDING_BASE_URL', 'http://ollama:11434'), ('LLM_MODEL_EMBEDDING', 'bge-m3'), ('LLM_EMBEDDING_DIMENSIONS', '1024')]:
        require(re.search(r'^' + key + '=' + re.escape(value) + '$', env, re.M), 'Example embedding drift: ' + key)
    defined = set(re.findall(r'^([A-Z][A-Z0-9_]*)=', env, re.M))
    for var in re.finditer(r'(?<!\$)\$\{([A-Z][A-Z0-9_]*)([^}]*)\}', compose):
        require(var[1] in defined or var[2].startswith(':-'), 'Missing example variable: ' + var[1])
    require('${OFFERPILOT_ENV_FILE:-../.env}' in backend, 'Backend env_file must use the interpolation file')
    require('backend: { condition: service_healthy }' in blocks.get('caddy', ''), 'Proxy must wait for backend')
    require('frontend: { condition: service_completed_successfully }' in blocks.get('caddy', ''), 'Proxy must wait for assets')
    require('/actuator/health' in backend and '/healthz' in blocks.get('caddy', ''), 'Invalid readiness routes')
    for path in ['backend/Dockerfile', 'frontend/Dockerfile', 'mcp-servers/jobs-mcp/Dockerfile', 'deploy/Caddyfile']:
        require((root / path).is_file(), 'Missing build or mount path: ' + path)
    config = (root / 'backend/offerpilot-bootstrap/src/main/resources/application.yml').read_text()
    require('embeddings-path: /v1/embeddings' in config and '${LLM_MODEL_EMBEDDING:bge-m3}' in config, 'Spring embedding drift')
    require('--profile full up -d --build' in readme and 'config --quiet' in readme and 'healthcheck.sh https://localhost' in readme, 'README startup/readiness drift')
    return errors


if __name__ == '__main__':
    errors = validate((ROOT / 'deploy/docker-compose.yml').read_text(), (ROOT / '.env.example').read_text(), (ROOT / 'README.md').read_text())
    for error in errors:
        print(error, file=sys.stderr)
    print('Offline Compose source checks: ' + ('FAILED' if errors else 'PASS (Docker startup remains untested)'))
    sys.exit(bool(errors))
