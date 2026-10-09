#!/usr/bin/env bash
set -euo pipefail
# Obtain an active STAFF JWT from POST /api/v1/auth/staff/login first.
if [[ -z "${STAFF_TOKEN:-}" ]]; then
  echo 'STAFF_TOKEN 未设置：请先使用客服账号登录 /api/v1/auth/staff/login，再导出返回的 accessToken。' >&2
  exit 1
fi
api_base="${API_BASE_URL:-http://localhost:8081/api/v1}"
curl --fail-with-body --silent --show-error \
  --connect-timeout 10 --max-time 120 \
  --request POST \
  --header "Authorization: Bearer ${STAFF_TOKEN}" \
  "${api_base%/}/admin/demo/reset"
printf '\n'
