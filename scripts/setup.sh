#!/usr/bin/env bash
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"
python3 - <<'PY'
import os
import secrets
from pathlib import Path

env_path = Path('.env')
if env_path.exists():
    print('기존 .env를 유지합니다.')
else:
    content = Path('.env.example').read_text()
    content = content.replace('change-me-app-password', secrets.token_hex(24))
    content = content.replace('change-me-root-password', secrets.token_hex(24))
    content = content.replace('change-me-ingest-key', secrets.token_hex(32))
    descriptor = os.open(env_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, 'w') as env_file:
        env_file.write(content)
    print('로컬 DB 비밀번호를 생성하고 .env에 저장했습니다.')
content = env_path.read_text()
if 'MONITOR_INGEST_API_KEY=' not in content:
    with env_path.open('a') as env_file:
        env_file.write('\nMONITOR_INGEST_API_KEY=' + secrets.token_hex(32) + '\n')
    print('로컬 이벤트 수신 인증키를 추가했습니다.')
PY
cd frontend
npm ci --no-audit --no-fund

