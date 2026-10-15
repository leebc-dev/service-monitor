#!/usr/bin/env python3
"""Temporarily stop this project's Redis and verify independent Kafka consumers."""
import runpy
import subprocess
from pathlib import Path

shared = runpy.run_path(str(Path(__file__).with_name('local-e2e.py')))
root, monitor, prefix = (shared[name] for name in ('ROOT', 'MONITOR', 'PREFIX'))
get, request, event, ingest, eventually = (shared[name] for name in ('get', 'request', 'event', 'ingest', 'eventually'))


def compose(*args):
    subprocess.run(['docker', 'compose', '--project-directory', str(root), '-f', str(root / 'compose.yaml'),
                    *args], check=True)


item = event('/api/v1/redis-recovery')
try:
    compose('stop', 'redis')
    assert ingest(item) == 202, 'Intake did not publish to Kafka during Redis outage'
    eventually(lambda: any(row['eventId'] == item['eventId'] for row in get(prefix + '/events')),
               'MySQL history continues while Redis is stopped')
    assert request(monitor, prefix + '/metrics')[0] >= 500
    print('PASS Redis metrics report failure instead of a false zero')
finally:
    compose('up', '-d', '--wait', 'redis')

eventually(lambda: get('/actuator/health/infrastructure')['status'] == 'UP', 'Infrastructure recovered')
eventually(lambda: any(row['route'] == 'GET ' + item['route'] for row in get(prefix + '/metrics')['routes']),
           'Redis consumer caught up from Kafka without restarting the backend')
