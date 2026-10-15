#!/usr/bin/env python3
"""Use isolated local fixture services to test alert delivery, Redis suppression and replay safety."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
import runpy
import subprocess
import time
from uuid import uuid4

shared = runpy.run_path(str(Path(__file__).with_name('local-e2e.py')))
ROOT, MONITOR = shared['ROOT'], shared['MONITOR']
request, get, eventually = (shared[name] for name in ('request', 'get', 'eventually'))


def docker(*args, input=None):
    result = subprocess.run(['docker', 'compose', *args], cwd=ROOT, input=input,
                            text=True, capture_output=True, check=True)
    return result.stdout.strip()


def mysql(sql):
    # Credentials stay inside the local container, never in command arguments or output.
    return docker('exec', '-T', 'mysql', 'sh', '-c',
                  'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql --batch --skip-column-names -u"$MYSQL_USER" "$MYSQL_DATABASE"', input=sql)


def redis(*args):
    return docker('exec', '-T', 'redis', 'redis-cli', '--raw', *args)


def key(service, suffix):
    return 'monitor:{' + service + ':development}:' + suffix


def make_event(service, status=200, duration=10):
    return dict(shared['event']('/api/v1/alerts-test'), serviceKey=service,
                statusCode=status, durationMs=duration, deploymentVersion='alerts-e2e')


def send(events):
    status, _ = request(MONITOR, '/api/v1/ingest/http-requests', {'events': events}, True)
    assert status == 202, f'Intake HTTP {status}'


def alerts(service):
    return get('/api/v1/services/' + service + '/alerts')['notifications']


def caught_up(service, count):
    eventually(lambda: int(redis('ZCARD', key(service, 'alerts:times'))) == count,
               f'Alert consumer processed {count} unique events')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--verify-cooldown', action='store_true', help='Also wait for real TTL expiry; run backend with cooldown 5–15s')
    options = parser.parse_args()
    response = get('/api/v1/services/football-lounge/alerts')
    if response.get('email', {}).get('enabled'):
        raise SystemExit('Email delivery is enabled. Use scripts/email-e2e.py with its local SMTP capture server, or disable email before this isolated alert test.')
    policy = response['policy']
    assert policy['enabled'] and policy['minimumRequests'] == 10
    assert policy['errorRatePercent'] == 10 and policy['averageDurationMs'] == 500
    if options.verify_cooldown:
        assert 5 <= policy['cooldownSeconds'] <= 15, 'Start backend with MONITOR_ALERT_COOLDOWN_SECONDS=8 for the TTL test'
    services = ['alert-test-' + uuid4().hex[:12] for _ in range(2)]
    first, second = services
    sql_names = ','.join("'" + service + "'" for service in services)  # Generated hex, not user input.
    try:
        for service in services:
            mysql(f"INSERT INTO monitored_service (service_key, display_name, environment) VALUES ('{service}', 'Isolated alert test', 'development');")
        send([make_event(first) for _ in range(9)])
        caught_up(first, 9)
        assert alerts(first) == []
        print('PASS no alert below minimum sample size')

        trigger = make_event(first, 500, 6000)
        send([trigger])
        eventually(lambda: len(alerts(first)) == 2, 'Error-rate and latency alerts both created')
        first_alerts = alerts(first)
        error = next(item for item in first_alerts if item['type'] == 'ERROR_RATE')
        latency = next(item for item in first_alerts if item['type'] == 'LATENCY')
        assert error['observedValue'] == 10 and latency['observedValue'] == 609
        cooldown_keys = [key(first, 'alert-cooldown:' + rule) for rule in ('ERROR_RATE', 'LATENCY')]
        eventually(lambda: all(int(redis('PTTL', name)) > 0 for name in cooldown_keys), 'Redis cooldown keys have real TTLs')
        original_ttl = int(redis('PTTL', cooldown_keys[0]))

        with ThreadPoolExecutor(max_workers=4) as pool:
            list(pool.map(lambda _: send([trigger]), range(4)))
        send([make_event(first, 500, 2000) for _ in range(6)])
        caught_up(first, 16)
        assert len(alerts(first)) == 2
        print('PASS duplicate events and repeated threshold breaches are suppressed')

        # Remove only this fixture's two keys to simulate Redis key loss after DB commit.
        redis('DEL', *cooldown_keys)
        send([make_event(first, 500, 2000)])
        caught_up(first, 17)
        eventually(lambda: all(int(redis('PTTL', name)) > 0 for name in cooldown_keys), 'Cooldown restored from durable notification time')
        assert len(alerts(first)) == 2
        assert int(redis('PTTL', cooldown_keys[0])) < original_ttl, 'Retry extended the cooldown'
        print('PASS Redis key loss does not duplicate alerts or extend suppression')

        send([make_event(second, 500, 2000) for _ in range(10)])
        eventually(lambda: len(alerts(second)) == 2, 'Another service has independent alert cooldowns')

        final_count = 17
        if options.verify_cooldown:
            latest = max(datetime.fromisoformat(item['createdAt'].replace('Z', '+00:00')) for item in first_alerts)
            wait = latest.timestamp() + policy['cooldownSeconds'] - datetime.now(timezone.utc).timestamp() + 0.2
            if wait > 0:
                time.sleep(wait)
            assert all(int(redis('EXISTS', name)) == 0 for name in cooldown_keys)
            send([make_event(first, 500, 2000)])
            eventually(lambda: len(alerts(first)) == 4, 'After actual TTL expiry, continuing breaches produce new alerts')
            final_count += 1
        for service, count in [(first, final_count), (second, 10)]:
            eventually(lambda: int(mysql(f"SELECT COUNT(*) FROM http_request_event WHERE service_key = '{service}';")) == count
                       and int(redis('ZCARD', key(service, 'times'))) == count,
                       'History and live-metrics consumers still process all unique events')
    finally:
        # Delete only the fixtures created by this invocation; Football Lounge data is preserved.
        for table in ('alert_notification', 'alert_rule_state', 'http_request_event', 'monitored_service'):
            mysql(f'DELETE FROM {table} WHERE service_key IN ({sql_names});')
        for service in services:
            redis('DEL', *(key(service, suffix) for suffix in ('times', 'events', 'alerts:times', 'alerts:events',
                         'alert-cooldown:ERROR_RATE', 'alert-cooldown:LATENCY')))
        print('Removed isolated local alert test fixtures.')


if __name__ == '__main__':
    main()
