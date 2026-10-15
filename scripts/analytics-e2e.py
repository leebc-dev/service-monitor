#!/usr/bin/env python3
"""Verify MySQL analytics using isolated history rows; never publishes events or emails."""
from datetime import datetime, timezone, timedelta
from pathlib import Path
import runpy
from uuid import uuid4

shared = runpy.run_path(str(Path(__file__).with_name('alerts-e2e.py')))
get, mysql, request = (shared[name] for name in ('get', 'mysql', 'request'))
service = 'analytics-test-' + uuid4().hex[:12]
prefix = '/api/v1/services/' + service

try:
    mysql(f"INSERT INTO monitored_service(service_key, display_name, environment) VALUES ('{service}', 'Analytics fixture', 'development');")
    now = datetime.now(timezone.utc)
    rows = []
    for i in range(1, 21):
        occurred = (now - timedelta(seconds=180 - i * 2)).strftime('%Y-%m-%d %H:%M:%S.%f')
        route = '/first' if i <= 10 else '/second'
        status = 500 if i > 18 else 404 if i == 18 else 200
        rows.append(f"('{uuid4()}', '{service}', 'development', 'GET', '{route}', {status}, {i * 10}, '{occurred}', 'analytics-test')")
    # Same service in another environment and outside all supported time ranges must be excluded.
    old = (now - timedelta(hours=2)).strftime('%Y-%m-%d %H:%M:%S.%f')
    rows.extend([f"('{uuid4()}', '{service}', 'production', 'GET', '/excluded', 500, 9999, '{occurred}', 'analytics-test')",
                 f"('{uuid4()}', '{service}', 'development', 'GET', '/excluded', 500, 9999, '{old}', 'analytics-test')"])
    mysql('INSERT INTO http_request_event(event_id,service_key,environment,method,route,status_code,duration_ms,occurred_at,deployment_version) VALUES ' + ','.join(rows) + ';')
    for minutes, step in [(5, 10), (15, 30), (60, 120)]:
        data = get(prefix + f'/analytics?minutes={minutes}')
        summary = data['summary']
        assert summary == {'requestCount': 20, 'serverErrorCount': 2, 'serverErrorRate': 10.0,
                           'averageDurationMs': 105.0, 'p95DurationMs': 190.0, 'maxDurationMs': 200.0}, summary
        assert len(data['buckets']) == 30 and data['bucketSeconds'] == step
        assert sum(b['metrics']['requestCount'] for b in data['buckets']) == 20
        assert sum(b['metrics']['serverErrorCount'] for b in data['buckets']) == 2
        for bucket in data['buckets']:
            if bucket['metrics']['requestCount'] == 0:
                assert bucket['metrics']['serverErrorRate'] is None
                assert bucket['metrics']['averageDurationMs'] is None
                assert bucket['metrics']['p95DurationMs'] is None
        assert len(data['events']) == 20
        routes = {r['route']: r['metrics'] for r in data['routes']}
        assert routes['/first']['p95DurationMs'] == 100
        assert routes['/second']['p95DurationMs'] == 200
        assert routes['/second']['serverErrorRate'] == 20
        print(f'PASS {minutes}m: exact summary/P95, route grouping, buckets and environment/time isolation', flush=True)
    empty = get(prefix + '/analytics?environment=empty')
    assert empty['summary']['requestCount'] == 0 and empty['summary']['p95DurationMs'] is None
    assert not empty['events'] and not empty['routes']
    for query in ['minutes=0', 'minutes=1440', 'minutes=x', 'environment=bad%27key']:
        assert request(shared['MONITOR'], prefix + '/analytics?' + query)[0] == 400
    assert mysql(f"SELECT COUNT(*) FROM alert_notification WHERE service_key='{service}';") == '0'
    print('PASS empty ranges, invalid parameters and no alert/email side effects', flush=True)
finally:
    mysql(f"DELETE FROM http_request_event WHERE service_key='{service}';")
    mysql(f"DELETE FROM monitored_service WHERE service_key='{service}';")
