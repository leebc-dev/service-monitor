#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"
case "${1:-up}" in
  up) docker compose up -d --wait --wait-timeout 180 ;;
  down) docker compose down ;;
  status) docker compose ps ;;
  logs) docker compose logs --tail=80 "${@:2}" ;;
  *) echo 'Usage: ./scripts/infra.sh [up|down|status|logs [service]]' >&2; exit 1 ;;
esac

