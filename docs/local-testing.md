# 로컬 테스트 가이드

## 실행 순서

`service-monitor` 디렉터리에서 실행합니다.

```bash
./scripts/setup.sh
./scripts/infra.sh up
```

다음 세 명령은 각각 별도 터미널에서 계속 실행해 둡니다.

```bash
./scripts/backend.sh
./scripts/frontend.sh
python3 scripts/football-local.py
```

화면은 http://127.0.0.1:15273 입니다. 풋볼라운지 기본 위치는 `~/IdeaProjects/football_link/server`이며 다른 위치라면 다음처럼 실행합니다.

```bash
FOOTBALL_SERVER_DIR=/path/to/football_link/server python3 scripts/football-local.py
```

`football-local.py`는 풋볼라운지를 빌드하고 `monitoring-local` 프로필로 실행합니다. 명령행에서 `runtime.env.enabled=false`를 지정해 기존 `runtime.env` 로더를 비활성화하고, DB·AWS·메일 관련 환경변수를 상속하지 않습니다. 전용 수집 키만 `.env`에서 읽어 환경변수로 전달합니다.

풋볼라운지는 H2 메모리 DB를 사용합니다. 업무 테이블과 운영 데이터는 복사하지 않았으므로 로그인·경기 조회 등 전체 서비스 기능을 확인하는 환경은 아닙니다. 기존 앱의 HTTP·보안·필터 경로와 로컬 전용 진단 API를 검증합니다. FCM·메일·SMS 큐와 외부 인증 기능도 로컬 프로필에서 비활성화합니다.

## 테스트 요청 보내기

```bash
python3 scripts/local-e2e.py
```

이 스크립트는 로컬 풋볼라운지에 다음 요청을 보냅니다.

| 경로 | 예상 결과 |
|---|---|
| `/api/v1/monitoring-demo/ok` | 정상 200 |
| `/api/v1/monitoring-demo/slow` | 기본 약 300ms 지연 후 200, `?delayMs=2000`으로 2초 지정 가능(0–3000ms) |
| `/api/v1/monitoring-demo/error` | 의도적으로 만든 500 |
| `/api/v1/monitoring-demo/items/101`, `/202` | 같은 `{itemId}` 라우트로 집계 |
| `/api/v1/monitoring-private-check` | 기존 보안 설정에 따른 비인증 거절 |

헬스 체크와 `/api/v1/monitoring-demo/sender` 진단은 수집에서 제외됩니다. 스크립트는 추가로 수신 API에 동일 ID 이벤트를 두 번 보내 중복 저장·집계를 확인하고 잘못된 키·필드·시각을 검사합니다. 테스트 이벤트는 `local-monitoring` 또는 `local-e2e` 배포 버전으로 남습니다.

수동으로 요청 한 건을 보낼 수도 있습니다.

```bash
curl http://127.0.0.1:18090/api/v1/monitoring-demo/ok
curl http://127.0.0.1:18090/api/v1/monitoring-demo/slow
curl http://127.0.0.1:18090/api/v1/monitoring-demo/error
curl http://127.0.0.1:18090/api/v1/monitoring-demo/sender
```

대시보드의 새로고침 버튼을 누르거나 15초 자동 갱신을 기다립니다. 500은 의도한 테스트 결과이므로 해당 이벤트가 오류율에 반영되는 것이 정상입니다.

## Redis 장애·복구 실험

모든 앱이 실행된 상태에서:

```bash
python3 scripts/redis-recovery.py
```

이 프로젝트의 Redis 컨테이너만 중지하고 새 이벤트를 보냅니다. MySQL 이력이 계속 저장되는지, 지표 조회가 오류를 표시하는지 확인합니다. `finally`에서 Redis를 재시작하고 백엔드를 재시작하지 않아도 Kafka 소비자가 따라잡는지 검증합니다. 프로세스를 강제 종료했다면 `./scripts/infra.sh up`으로 복구하세요.

## 수신 서버 중단 실험

모니터링 백엔드 터미널에서 `Ctrl+C`를 누른 후 풋볼라운지의 `/ok`를 몇 번 호출합니다. 정상 200이 반환됩니다. 수초 후 `/sender`에서 `failedAttempts`, `dropped` 증가를 확인합니다. 백엔드를 `./scripts/backend.sh`로 다시 실행하면 이후 요청부터 수집이 재개됩니다. 이미 버린 이벤트를 복구하는 실험은 아닙니다.

## 풋볼라운지 변경 위치

`football_link/server` 기준:

- `src/main/java/com/football/server/monitoring/`: 필터, 전송 큐, 설정, 이벤트, 로컬 진단 컨트롤러.
- `src/main/resources/application-monitoring-local.yml`: H2와 수집 설정.
- `src/main/java/com/football/server/config/RuntimeEnvEnvironmentPostProcessor.java`: 명시적으로 `runtime.env.enabled=false`이면 로딩을 건너뛰는 옵션 추가. 기본 동작은 유지합니다.
- `src/test/java/com/football/server/monitoring/MonitoringRequestFilterTest.java`: 경로 정규화, 실패 격리, 제외 경로, 인증 거절 수집 검사.

일반 프로필은 `monitoring.enabled`가 설정되지 않아 수집 모듈이 비활성화됩니다. 진단 컨트롤러와 전용 보안 체인은 `monitoring-local`에서만 활성화됩니다. 기존 SecurityConfig나 업무 로직은 변경하지 않았습니다.

## 검증한 내용

2026-10-14, 이 로컬 PC에서 확인했습니다.

| 검증 | 결과 |
|---|---|
| 모니터링 통계 2개·알림 12개·이메일 12개 단위 테스트 | 통과 |
| 풋볼라운지 수집 필터 단위 테스트 4개 | 통과 |
| 양쪽 백엔드 빌드 / Vue 타입 검사·빌드 | 통과 |
| 로컬 HTTP → Kafka → MySQL / Redis | 통과 |
| 500·약 300ms 지연·비인증 거절 수집 | 통과 |
| 라우트 템플릿 집계, 쿼리 문자열 제외 | 통과 |
| 동일 ID 재전송의 중복 저장·집계 방지 | 통과 |
| 수신 키 누락·잘못된 이벤트 거절 | 통과 |
| Redis 중단 중 MySQL 이력 저장 | 통과 |
| Redis 복구 후 Kafka 소비 재개 | 통과 |
| 수신 서버 중단 중 풋볼라운지 200 응답 유지 | 5건, 측정 최대 1.8ms; 전송 재시도 후 드롭 확인 |

위 응답 시간은 로컬 진단 API를 5번 호출한 관측값이며 운영 성능 보장이 아닙니다. 이후 추가한 실제 60건 HTTP 요청→Gmail 수신·반복 억제 검증은 [실제 알림 테스트](live-alert-test.md), 시계열·P95 집계는 [대시보드 가이드](dashboard.md)에 정리했습니다. 전체 풋볼라운지 업무 테스트, 부하 시험, 운영 서버 연결은 이번 검증 범위에 포함하지 않습니다.
