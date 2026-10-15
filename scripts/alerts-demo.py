#!/usr/bin/env python3
"""Create visible local dashboard alerts: real demo 500s and explicitly synthetic latency samples."""
from pathlib import Path
import runpy

shared = runpy.run_path(str(Path(__file__).with_name('local-e2e.py')))
request = shared['request']

for _ in range(10):
    status, _ = request(shared['FOOTBALL'], '/api/v1/monitoring-demo/error')
    assert status == 500, 'The local Football Lounge demo server must be running on port 18090'
print('Sent 10 intentional HTTP 500 requests to local Football Lounge.')

events = [dict(shared['event']('/api/v1/synthetic-alert-demo/slow'), durationMs=2000,
               deploymentVersion='synthetic-alert-demo') for _ in range(10)]
status, _ = request(shared['MONITOR'], '/api/v1/ingest/http-requests', {'events': events}, True)
assert status == 202
print('Sent 10 synthetic 2000ms events directly to the local collector; these are not measured HTTP latencies.')
print('Open http://127.0.0.1:15273/#alerts and refresh. Existing traffic or cooldowns may affect new alerts.')
