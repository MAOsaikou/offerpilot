#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
mkdir -p backups
TS=$(date +%F_%H%M)
docker compose -f deploy/docker-compose.yml exec -T postgres pg_dump -U "${POSTGRES_USER:-offerpilot}" "${POSTGRES_DB:-offerpilot}" | gzip > "backups/pg_$TS.sql.gz"
find backups -type f -mtime +7 -delete
