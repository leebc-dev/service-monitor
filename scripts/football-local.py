#!/usr/bin/env python3
"""Run the real Football Lounge application with a private in-memory database and demo routes."""
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
football = Path(os.environ.get('FOOTBALL_SERVER_DIR', str(Path.home() / 'IdeaProjects/football_link/server')))
config = {}
for line in (root / '.env').read_text().splitlines():
    if line.strip() and not line.lstrip().startswith('#') and '=' in line:
        key, value = line.split('=', 1)
        config[key] = value
if len(config.get('MONITOR_INGEST_API_KEY', '')) < 32:
    raise SystemExit('Run ./scripts/setup.sh first.')
if not (football / 'src/main/resources/application-monitoring-local.yml').exists():
    raise SystemExit('Football Lounge monitoring integration is not installed. See docs/local-testing.md.')

# Do not inherit production database, AWS, mail or application environment variables.
env = {key: value for key, value in os.environ.items() if key in
       {'PATH', 'HOME', 'USER', 'LOGNAME', 'SHELL', 'JAVA_HOME', 'LANG', 'TMPDIR'}}
env.update({
    'AWS_EC2_METADATA_DISABLED': 'true',
    'MONITORING_API_KEY': config['MONITOR_INGEST_API_KEY'],
    'MONITORING_COLLECTOR_URL': 'http://127.0.0.1:' + config.get('BACKEND_PORT', '18180'),
})
subprocess.run(['./gradlew', 'bootJar', '--console=plain'], cwd=football, env=env, check=True)
os.chdir(football)
os.execvpe('java', ['java', '-Xms128m', '-Xmx512m', '-jar', 'build/libs/football-api.jar',
    '--spring.profiles.active=monitoring-local', '--runtime.env.enabled=false',
    '--server.address=127.0.0.1', '--server.port=18090',
    '--spring.datasource.url=jdbc:h2:mem:football_monitoring_local;MODE=MYSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE',
    '--spring.datasource.driver-class-name=org.h2.Driver', '--spring.datasource.username=sa',
    '--spring.datasource.password=', '--spring.sql.init.mode=never',
    '--app.fcm.enabled=false', '--app.mail.enabled=false', '--app.sms-queue.enabled=false',
    '--app.watch.artifacts.provider=local', '--app.upload.provider=local', '--app.auth.kcp-cert.enabled=false',
], env)
