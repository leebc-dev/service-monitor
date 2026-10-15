#!/usr/bin/env python3
"""Exercise local Football Lounge requests and both Kafka consumers; no production calls."""
import json
from datetime import datetime, timezone, timedelta
from pathlib import Path
import time
from urllib.request import Request, urlopen
from urllib.error import HTTPError
from uuid import uuid4

ROOT = Path(__file__).resolve().parent.parent
CONFIG = dict(line.split('=', 1) for line in (ROOT / '.env').read_text().splitlines()
              if line.strip() and not line.lstrip().startswith('#') and '=' in line)
MONITOR = 'http://127.0.0.1:' + CONFIG.get('BACKEND_PORT', '18180')
FOOTBALL = 'http://127.0.0.1:18090'
PREFIX = '/api/v1/services/football-lounge'


def request(base, path, body=None, authenticated=False):
    headers = {'Accept': 'application/json'}
    if authenticated:
        headers['X-Monitor-Key'] = CONFIG['MONITOR_INGEST_API_KEY']
    if body is not None:
        headers['Content-Type'] = 'application/json'
    req = Request(base + path, data=None if body is None else json.dumps(body).encode(), headers=headers)
    try:
        with urlopen(req, timeout=8) as response:
            return response.status, json.load(response)
    except HTTPError as error:
        return error.code, json.loads(error.read() or '{}')


def get(path):
    status, body = request(MONITOR, path)
    assert status == 200, f'{path}: HTTP {status}'
    return body


def eventually(check, description, timeout=25):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if check():
            print('PASS ' + description)
            return
        time.sleep(0.3)
    raise AssertionError('Timed out: ' + description)


def event(route=None):
    return {'eventId': str(uuid4()), 'serviceKey': 'football-lounge', 'environment': 'development',
            'method': 'GET', 'route': route or '/api/v1/local-smoke/' + uuid4().hex,
            'statusCode': 200, 'durationMs': 12, 'occurredAt': datetime.now(timezone.utc).isoformat(),
            'deploymentVersion': 'local-e2e'}


def ingest(item, authenticated=True):
    return request(MONITOR, '/api/v1/ingest/http-requests', {'events': [item]}, authenticated)[0]


def main():
    assert get('/actuator/health/infrastructure')['status'] == 'UP'
    started = datetime.now(timezone.utc)
    marker = 'query-marker-' + uuid4().hex
    for path, expected in [('/ok', 200), ('/slow', 200), ('/error', 500),
                           ('/items/101', 200), ('/items/202', 200)]:
        status, _ = request(FOOTBALL, '/api/v1/monitoring-demo' + path + '?token=' + marker)
        assert status == expected, f'demo {path}: {status}'
    status, _ = request(FOOTBALL, '/api/v1/monitoring-private-check')
    assert status in (401, 403), f'Expected unauthenticated rejection, got {status}'
    auth_status = status
    assert request(FOOTBALL, '/api/v1/public/health')[0] == 200
    assert request(FOOTBALL, '/api/v1/monitoring-demo/sender')[0] == 200

    def captured():
        rows = [row for row in get(PREFIX + '/events')
                if datetime.fromisoformat(row['occurredAt'].replace('Z', '+00:00')) >= started]
        if len(rows) < 6:
            return False
        assert marker not in json.dumps(rows), 'Query string leaked into monitoring data'
        assert not any('/health' in row['route'] or '/sender' in row['route'] for row in rows)
        assert any(row['statusCode'] == 500 for row in rows)
        assert any(row['statusCode'] == auth_status and row['route'] == 'UNMATCHED' for row in rows)
        assert any(row['route'].endswith('/slow') and row['durationMs'] >= 250 for row in rows)
        assert sum(row['route'].endswith('/items/{itemId}') for row in rows) == 2
        return True

    eventually(captured, 'Football HTTP → Kafka → MySQL: 200 / delay / 500 / auth rejection / route template')
    eventually(lambda: get(PREFIX + '/metrics')['requestCount'] >= 6,
               'Kafka → Redis: recent request metrics')
    assert get('/api/v1/services')[0]['collectionStatus'] == 'RECEIVING'

    duplicate = event()
    assert ingest(duplicate) == 202 and ingest(duplicate) == 202
    def deduplicated():
        rows = [row for row in get(PREFIX + '/events') if row['eventId'] == duplicate['eventId']]
        routes = [row for row in get(PREFIX + '/metrics')['routes'] if row['route'] == 'GET ' + duplicate['route']]
        return len(rows) == 1 and len(routes) == 1 and routes[0]['requestCount'] == 1
    eventually(deduplicated, 'Repeated eventId is stored and counted once')
    # Allow a second consumer poll so an assertion cannot pass before the duplicate arrives.
    time.sleep(1)
    assert deduplicated()
    assert ingest(event(), authenticated=False) == 401
    assert ingest(dict(event(), statusCode=999)) == 400
    assert ingest(dict(event(), serviceKey='unregistered')) == 400
    assert ingest(dict(event(), route='/api/items?token=forbidden')) == 400
    assert ingest(dict(event(), occurredAt=(datetime.now(timezone.utc) + timedelta(minutes=5)).isoformat())) == 400
    print('PASS intake authentication and invalid event rejection')
    print('Local E2E passed. Test requests remain visible in MySQL; Redis window is 5 minutes.')


if __name__ == '__main__':
    main()
