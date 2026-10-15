#!/usr/bin/env python3
"""Isolated SMTP capture + real local backend/DB/Kafka test. Never contacts Gmail."""
import email.policy
from email.parser import BytesParser
import os
from pathlib import Path
import runpy
import socket
import socketserver
import subprocess
import threading
import time
from uuid import uuid4

shared = runpy.run_path(str(Path(__file__).with_name('alerts-e2e.py')))
ROOT, MONITOR = shared['ROOT'], shared['MONITOR']
request, get, mysql, redis, key = (shared[name] for name in ('request', 'get', 'mysql', 'redis', 'key'))
backend_port = int(MONITOR.rsplit(':', 1)[1])


class Capture:
    def __init__(self):
        self.lock = threading.Lock()
        self.fail_next = 2
        self.always_fail = False
        self.messages = []

    def reject(self):
        with self.lock:
            if self.always_fail:
                return True
            if self.fail_next:
                self.fail_next -= 1
                return True
            return False


capture = Capture()


class SmtpHandler(socketserver.StreamRequestHandler):
    def reply(self, text):
        self.wfile.write(text.encode() + b'\r\n')
        self.wfile.flush()

    def handle(self):
        self.connection.settimeout(15)
        self.reply('220 localhost local-test-only')
        while True:
            line = self.rfile.readline(65536)
            if not line:
                break
            command = line.decode(errors='replace').strip().upper()
            if command.startswith(('EHLO', 'HELO')):
                self.reply('250-localhost')
                self.reply('250 SIZE 1048576')
            elif command == 'DATA':
                if capture.reject():
                    self.reply('451 4.3.0 Deliberate local temporary failure')
                    continue
                self.reply('354 End data with <CRLF>.<CRLF>')
                lines = []
                size = 0
                while True:
                    data = self.rfile.readline(65536)
                    if data in (b'.\r\n', b'.\n'):
                        break
                    if not data:
                        return
                    size += len(data)
                    if size > 1048576:
                        return
                    lines.append(data[1:] if data.startswith(b'..') else data)
                message = BytesParser(policy=email.policy.default).parsebytes(b''.join(lines))
                with capture.lock:
                    capture.messages.append(message)
                self.reply('250 2.0.0 Captured locally')
            elif command == 'QUIT':
                self.reply('221 Bye')
                break
            elif command.startswith(('MAIL FROM:', 'RCPT TO:', 'RSET', 'NOOP')):
                self.reply('250 OK')
            else:
                self.reply('502 Unsupported local test command')


def wait(check, label, timeout=35):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if check():
            print('PASS ' + label, flush=True)
            return
        time.sleep(0.2)
    raise AssertionError('Timed out: ' + label)


def delivery_status(id):
    status, body = request(MONITOR, '/api/v1/notifications/email/deliveries/' + id, authenticated=True)
    assert status == 200
    return body


def main():
    with socket.socket() as connection:
        if connection.connect_ex(('127.0.0.1', backend_port)) == 0:
            raise SystemExit('Stop the local monitoring backend before running this test; it manages its own test process.')
    if mysql("SHOW TABLES LIKE 'email_outbox';"):
        pending = int(mysql("SELECT COUNT(*) FROM email_outbox WHERE status IN ('PENDING','RETRY','SENDING');"))
        if pending:
            raise SystemExit('Real email work is still pending. Finish it before using the local SMTP test process.')
    server = socketserver.ThreadingTCPServer(('127.0.0.1', 0), SmtpHandler)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    # Explicit overrides ensure existing Gmail credentials are never used by this test process.
    env = {k: v for k, v in os.environ.items() if k in {'PATH', 'HOME', 'USER', 'LOGNAME', 'SHELL', 'JAVA_HOME', 'LANG', 'TMPDIR'}}
    env.update({'MONITOR_EMAIL_ENABLED': 'true', 'SMTP_HOST': '127.0.0.1', 'SMTP_PORT': str(server.server_address[1]),
                'SMTP_AUTH': 'false', 'SMTP_STARTTLS_ENABLED': 'false', 'SMTP_SSL_ENABLED': 'false',
                'SMTP_USERNAME': 'unused-local-user', 'SMTP_PASSWORD': 'unused-local-password',
                'MONITOR_EMAIL_FROM': 'sender@example.test', 'MONITOR_EMAIL_TO': 'recipient@example.test',
                'MONITOR_EMAIL_MAX_ATTEMPTS': '3', 'MONITOR_EMAIL_RETRY_DELAY_SECONDS': '1',
                'MONITOR_EMAIL_POLL_INTERVAL_MS': '200', 'MONITOR_ALERTS_ENABLED': 'true',
                'MONITOR_ALERT_MINIMUM_REQUESTS': '10', 'MONITOR_ALERT_ERROR_RATE_PERCENT': '10',
                'MONITOR_ALERT_AVERAGE_DURATION_MS': '500', 'MONITOR_ALERT_COOLDOWN_SECONDS': '600',
                'MYSQL_HOST': '127.0.0.1', 'REDIS_HOST': '127.0.0.1'})
    (ROOT / '.runtime').mkdir(exist_ok=True)
    log = (ROOT / '.runtime/email-e2e-backend.log').open('w')
    process = None
    service = 'email-test-' + uuid4().hex[:12]
    created = False
    test_ids = []

    def stop():
        nonlocal process
        if process and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        process = None

    def start():
        nonlocal process
        process = subprocess.Popen(['java', '-Xms128m', '-Xmx512m', '-jar', 'build/libs/service-monitor-api.jar'],
                                   cwd=ROOT / 'backend', env=env, stdout=log, stderr=subprocess.STDOUT)
        def ready():
            if process.poll() is not None:
                raise AssertionError('Test backend exited; inspect .runtime/email-e2e-backend.log')
            try:
                return get('/actuator/health/infrastructure')['status'] == 'UP'
            except (OSError, AssertionError):
                return False
        wait(ready, 'Local test backend is ready')

    def queue_test(request_id=None):
        request_id = request_id or str(uuid4())
        status, result = request(MONITOR, '/api/v1/notifications/email/test', {'requestId': request_id}, True)
        assert status == 202
        test_ids.append(result['deliveryId'])
        return request_id, result['deliveryId']

    try:
        start()
        channel = get('/api/v1/services/football-lounge/alerts')['email']
        assert channel['enabled'] and channel['recipient'] == 'r***@example.test'
        mysql(f"INSERT INTO monitored_service (service_key, display_name, environment) VALUES ('{service}', 'Local SMTP test', 'development');")
        created = True
        shared['send']([shared['make_event'](service, 500, 2000) for _ in range(10)])
        wait(lambda: len(shared['alerts'](service)) == 2 and all(row['email']['status'] == 'SENT' for row in shared['alerts'](service)),
             'Kafka alerts persisted and both emails retried to SMTP acceptance')
        rows = shared['alerts'](service)
        assert all(row['email']['attempts'] == 2 for row in rows)
        assert len(capture.messages) == 2
        assert all(service in message.get_content() and '요청 10건' in message.get_content() for message in capture.messages)
        assert all(str(message['To']) == 'recipient@example.test' for message in capture.messages)

        shared['send']([shared['make_event'](service, 500, 2000) for _ in range(10)])
        wait(lambda: int(redis('ZCARD', key(service, 'alerts:times'))) == 20, 'Repeated breaches consumed')
        time.sleep(0.5)
        assert len(shared['alerts'](service)) == 2 and len(capture.messages) == 2
        print('PASS Redis suppression also prevents duplicate email jobs', flush=True)

        assert request(MONITOR, '/api/v1/notifications/email/test', {'requestId': str(uuid4())})[0] == 401
        capture.always_fail = True
        request_id, id = queue_test()
        wait(lambda: delivery_status(id)['status'] == 'RETRY', 'Retry state is durable before restart')
        stop()
        capture.always_fail = False
        start()
        wait(lambda: delivery_status(id)['status'] == 'SENT', 'Queued email survives backend restart')
        _, same_id = queue_test(request_id)
        assert same_id == id
        time.sleep(0.5)
        assert sum(message['X-Monitor-Delivery-ID'] == id for message in capture.messages) == 1
        print('PASS repeated test request ID does not send another email', flush=True)

        capture.always_fail = True
        _, failed_id = queue_test()
        wait(lambda: delivery_status(failed_id)['status'] == 'FAILED', 'Retry limit moves the email to FAILED')
        assert delivery_status(failed_id)['attempts'] == 3
        assert delivery_status(failed_id)['lastErrorCode'] == 'SMTP_SEND'
        capture.always_fail = False

        stale_id = str(uuid4())
        test_ids.append(stale_id)
        mysql(f"""INSERT INTO email_outbox
            (delivery_id,sender,recipient,subject,body,status,attempt_count,next_attempt_at,lock_token,locked_until,created_at)
            VALUES ('{stale_id}','sender@example.test','recipient@example.test','Local stale lease test','Recovered job',
            'SENDING',1,UTC_TIMESTAMP(6),'{uuid4()}',UTC_TIMESTAMP(6)-INTERVAL 1 SECOND,UTC_TIMESTAMP(6));""")
        wait(lambda: delivery_status(stale_id)['status'] == 'SENT', 'Expired worker lease is recovered')
        assert delivery_status(stale_id)['attempts'] == 2
        print('Email integration passed using only the local SMTP capture server.', flush=True)
    finally:
        stop()
        server.shutdown()
        server.server_close()
        log.close()
        if created:
            for table in ('alert_notification', 'alert_rule_state', 'http_request_event', 'monitored_service'):
                mysql(f"DELETE FROM {table} WHERE service_key = '{service}';")
            redis('DEL', *(key(service, suffix) for suffix in ('times', 'events', 'alerts:times', 'alerts:events',
                           'alert-cooldown:ERROR_RATE', 'alert-cooldown:LATENCY')))
        for id in set(test_ids):
            mysql(f"DELETE FROM email_outbox WHERE delivery_id = '{id}';")
        print('Stopped local SMTP/backend test processes and removed this run\'s fixtures.', flush=True)


if __name__ == '__main__':
    main()
