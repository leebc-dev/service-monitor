#!/usr/bin/env python3
"""Send one real test email to the configured recipient; retries reuse the printed request ID."""
import argparse
from pathlib import Path
import runpy
import time
from uuid import UUID, uuid4

shared = runpy.run_path(str(Path(__file__).with_name('local-e2e.py')))
parser = argparse.ArgumentParser()
parser.add_argument('--request-id', type=UUID, default=None, help='Inspect the same existing job without scheduling another email')
args = parser.parse_args()
request_id = str(args.request_id or uuid4())
channel = shared['get']('/api/v1/services/football-lounge/alerts')['email']
if not channel['enabled'] or not channel['configured']:
    raise SystemExit('Email is disabled or incomplete. Configure .env and restart the backend first.')
status, queued = shared['request'](shared['MONITOR'], '/api/v1/notifications/email/test', {'requestId': request_id}, True)
assert status == 202, f'Test email request failed: HTTP {status}'
print('Test request ID: ' + request_id, flush=True)
print('Delivery ID: ' + queued['deliveryId'], flush=True)
print('Configured recipient: ' + channel['recipient'], flush=True)
deadline = time.monotonic() + 45
while time.monotonic() < deadline:
    status, delivery = shared['request'](shared['MONITOR'], '/api/v1/notifications/email/deliveries/' + queued['deliveryId'], authenticated=True)
    assert status == 200
    if delivery['status'] == 'SENT':
        print('PASS Gmail/SMTP accepted the test email. Inbox arrival must be checked separately.', flush=True)
        break
    if delivery['status'] == 'FAILED':
        raise SystemExit('FAILED: ' + str(delivery['lastErrorCode']))
    time.sleep(1)
else:
    print('Still queued/retrying: ' + str(delivery['status']) + ' / ' + str(delivery['lastErrorCode']))
    print('Rerun with --request-id ' + request_id + ' to inspect the same job without creating another message.')
