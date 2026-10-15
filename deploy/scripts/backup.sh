#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
mkdir -p backups
TS=$(date +%F_%H%M%S)
backup_directory=$(mktemp -d "backups/pg_$TS-XXXXXX")
# Use the actual container configuration, including non-default database/user names.
docker compose -f deploy/docker-compose.yml exec -T postgres \
  sh -c 'exec pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB"' | gzip > "$backup_directory/database.sql.gz.partial"
mv "$backup_directory/database.sql.gz.partial" "$backup_directory/database.sql.gz"
printf 'Database backup completed: %s/database.sql.gz\n' "$backup_directory"
# Retention is a manual operation: never delete files automatically or in batches.
