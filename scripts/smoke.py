#!/usr/bin/env python3
"""Read-only checks against the running local stack, including the Vite proxy."""
import json
import os
from pathlib import Path
from urllib.request import urlopen

root = Path(__file__).resolve().parent.parent
config = {}
if (root / '.env').exists():
    for line in (root / '.env').read_text().splitlines():
        if line.strip() and not line.lstrip().startswith('#') and '=' in line:
            key, value = line.split('=', 1)
            config[key.strip()] = value.strip()

def read(url):
    with urlopen(url, timeout=12) as response:
        return response.read()

try:
    backend_port = os.environ.get('BACKEND_PORT', config.get('BACKEND_PORT', '18180'))
    frontend_port = os.environ.get('FRONTEND_PORT', config.get('FRONTEND_PORT', '15273'))
    backend = f'http://127.0.0.1:{backend_port}'
    frontend = f'http://127.0.0.1:{frontend_port}'
    for label, base in [('backend', backend), ('frontend proxy', frontend)]:
        health = json.loads(read(base + '/actuator/health/infrastructure'))
        assert health['status'] == 'UP', f'{label}: infrastructure is not UP'
        for component in ['db', 'redis', 'kafka']:
            assert health['components'][component]['status'] == 'UP', f'{label}: {component} is not UP'
        services = json.loads(read(base + '/api/v1/services'))
        football = next(service for service in services if service['serviceKey'] == 'football-lounge')
        assert football['displayName'] == '풋볼라운지', 'UTF-8 service name mismatch'
        assert football['collectionStatus'] in ['NOT_CONNECTED', 'RECEIVING', 'IDLE'], 'Unknown collection status'
        print(f'PASS {label}: MySQL / Redis / Kafka and registered service')
    assert 'Service Monitor' in read(frontend).decode(), 'Frontend HTML missing'
    print('PASS frontend HTML')
except Exception as error:
    raise SystemExit(f'FAIL: {error}')

