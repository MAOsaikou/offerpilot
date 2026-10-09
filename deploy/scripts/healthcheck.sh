#!/bin/sh
set -eu
# Usage: healthcheck.sh https://demo.example.com
# PUBLIC_URL can also be set by cron; TLS verification is always enabled.
public_url="${1:-${PUBLIC_URL:-http://localhost}}"
body=$(curl --fail --silent --show-error --location --max-redirs 3 \
  --connect-timeout 10 --max-time 30 "${public_url%/}/actuator/health")
# Actuator may include component statuses: require the top-level status to be UP.
# Spring emits status as the first JSON field; reject HTML / empty / DOWN responses.
if ! printf '%s\n' "$body" | tr -d '[:space:]' | grep -Eq '^\{"status":"UP"[,}]'; then
  echo 'OfferPilot health probe failed: expected top-level status UP' >&2
  exit 1
fi
printf 'OfferPilot health UP\n'
