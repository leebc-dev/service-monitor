#!/usr/bin/env python3
"""Real local HTTP → Kafka → alerts → configured SMTP. Sends two real alert emails."""
from datetime import datetime, timezone
from pathlib import Path
import json
import runpy
import time

shared = runpy.run_path(str(Path(__file__).with_name('alerts-e2e.py')))
get, request, redis, mysql = (shared[name] for name in ('get', 'request', 'redis', 'mysql'))
ROOT = shared['ROOT']
PREFIX = '/api/v1/services/football-lounge'
FOOTBALL = 'http://127.0.0.1:18090'


def wait(check, label, seconds=45):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if check():
            print('PASS ' + label, flush=True)
            return
        time.sleep(0.5)
    raise AssertionError('Timed out: ' + label)


def main():
    response = get(PREFIX + '/alerts')
    assert response['email']['enabled'] and response['email']['configured']
    policy = response['policy']
    assert policy['enabled'] and policy['minimumRequests'] == 10
    assert policy['errorRatePercent'] == 10 and policy['averageDurationMs'] == 500
    assert policy['cooldownSeconds'] == 600
    assert get('/actuator/health/infrastructure')['status'] == 'UP'
    keys = [shared['key']('football-lounge', 'alert-cooldown:' + rule) for rule in ('ERROR_RATE', 'LATENCY')]
    if any(int(redis('PTTL', key)) > 0 for key in keys):
        raise SystemExit('A real cooldown is active. Wait for expiry before running; do not delete its keys.')
    if get(PREFIX + '/metrics')['requestCount']:
        raise SystemExit('Wait for the existing 5-minute request window to empty before this controlled test.')
    before = {row['alertId'] for row in response['notifications']}
    started = datetime.now(timezone.utc)
    measured = []

    def generate():
        for _ in range(10):
            status, _ = request(FOOTBALL, '/api/v1/monitoring-demo/error')
            assert status == 500
        for _ in range(10):
            start = time.monotonic()
            status, body = request(FOOTBALL, '/api/v1/monitoring-demo/slow?delayMs=2000')
            elapsed = round((time.monotonic() - start) * 1000)
            assert status == 200 and body['delayMs'] == 2000 and elapsed >= 1900
            measured.append(elapsed)

    def new_alerts():
        return [row for row in get(PREFIX + '/alerts')['notifications'] if row['alertId'] not in before]

    generate()
    wait(lambda: len(new_alerts()) == 2, 'actual HTTP errors and 2-second requests created both alerts')
    wait(lambda: all(row['email'] and row['email']['status'] == 'SENT' for row in new_alerts()),
         'Gmail accepted both real alert emails')
    first_ids = {row['alertId'] for row in new_alerts()}
    assert {row['type'] for row in new_alerts()} == {'ERROR_RATE', 'LATENCY'}
    initial_ttl = [int(redis('PTTL', key)) for key in keys]
    assert all(0 < ttl <= 600000 for ttl in initial_ttl)
    print('Repeating the same 20 real HTTP requests during the 10-minute cooldown.', flush=True)
    generate()
    for _ in range(20):
        assert request(FOOTBALL, '/api/v1/monitoring-demo/ok')[0] == 200
    wait(lambda: int(redis('ZCARD', shared['key']('football-lounge', 'alerts:times'))) == 60,
         'alert consumer processed all 60 actual HTTP requests')
    wait(lambda: get(PREFIX + '/metrics')['requestCount'] == 60,
         'live metrics include 20 errors, 20 slow and 20 normal requests')
    time.sleep(6)  # One mail worker poll after consumers have caught up.
    rows = new_alerts()
    assert {row['alertId'] for row in rows} == first_ids and len(rows) == 2
    final_ttl = [int(redis('PTTL', key)) for key in keys]
    assert all(0 < end < start for start, end in zip(initial_ttl, final_ttl))
    ids_sql = ','.join("'" + row['alertId'] + "'" for row in rows)
    assert mysql('SELECT COUNT(*) FROM email_outbox WHERE alert_id IN (' + ids_sql + ');') == '2'
    assert all(row['email']['status'] == 'SENT' and row['email']['attempts'] == 1 for row in rows)
    stamp = started.strftime('%Y-%m-%d %H:%M:%S.%f')
    assert mysql("SELECT COUNT(*) FROM http_request_event WHERE service_key='football-lounge' "
                 "AND environment='development' AND occurred_at >= '" + stamp + "';") == '60'
    print('PASS repeat breaches created no extra alerts or email jobs; Redis TTL decreased without reset.', flush=True)
    report = {'startedAt': started.isoformat(), 'finishedAt': datetime.now(timezone.utc).isoformat(),
              'source': FOOTBALL, 'requests': {'error': 20, 'slow': 20, 'normal': 20},
              'measuredSlowMs': {'min': min(measured), 'max': max(measured)},
              'alerts': rows, 'additionalAlertsOnRepeat': 0, 'emailJobs': 2,
              'cooldownInitialTtlMs': initial_ttl, 'cooldownFinalTtlMs': final_ttl,
              'inboxReceipt': 'Requires recipient confirmation; SENT means SMTP accepted.'}
    target = ROOT / '.runtime' / 'football-alerts-live-result.json'
    target.parent.mkdir(exist_ok=True)
    target.write_text(json.dumps(report, indent=2, ensure_ascii=False) + '\n')
    print('Result saved to .runtime/football-alerts-live-result.json', flush=True)


if __name__ == '__main__':
    main()
