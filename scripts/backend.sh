#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ ! -f "$PROJECT_ROOT/.env" ]]; then
  echo '먼저 ./scripts/setup.sh를 실행해 주세요.' >&2
  exit 1
fi
cd "$PROJECT_ROOT/backend"
exec ./gradlew bootRun --console=plain

