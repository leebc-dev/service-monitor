# Service Monitor

HTTP 요청량·오류·응답 시간을 수집하고, 기준을 초과하면 대시보드와 이메일로 알리는 개인 프로젝트입니다. **수신 API → Kafka → MySQL / Redis → Vue** 흐름을 구현하고 풋볼라운지 개발 환경에 연동했습니다.

대시보드: [http://127.0.0.1:15273](http://127.0.0.1:15273)

모니터링 서버와 대시보드는 이 저장소만으로 실행할 수 있습니다. 풋볼라운지의 실제 HTTP 요청 수집을 재현하려면 별도 풋볼라운지 저장소가 필요하며, 이 저장소에는 해당 서비스의 소스가 포함되지 않습니다. 운영 데이터나 EC2 트래픽을 수집하는 상태는 아닙니다.

## 핵심 설계

- **처리 목적 분리:** Kafka 소비자 그룹을 이력 저장·최근 지표 집계·알림 판단으로 나누어 처리 진도를 각각 관리
- **중복과 반복 구분:** 이벤트 ID로 이력 중복을 방지하고, Redis로 같은 장애 알림의 반복을 억제
- **발송 실패 대응:** 알림과 이메일 작업을 같은 트랜잭션으로 기록하고, 별도 작업자에서 재시도·중단 복구 처리
- **수집 데이터 최소화:** 라우트 패턴·상태 코드·처리 시간만 수집하고 요청 본문·쿼리·인증 정보는 제외

구조도와 선택한 방식의 한계는 [아키텍처 설명](docs/architecture.md)에 정리했습니다. 소비자들은 하나의 애플리케이션에서 실행하며, 단일 Kafka 브로커를 사용하는 개발용 구성입니다.

## 구성

| 구성 요소 | 역할 |
|---|---|
| Spring Boot 4.1.1 / Java 21 | 이벤트 인증·검증·Kafka 발행, 이력·지표 조회 |
| Vue 3.5 / TypeScript / Vite 8.3 | 기간별 차트·P95, API 분석·요청 탐색, 알림 타임라인·이메일 상태 |
| MySQL 8.4 | 요청·알림 이력, 알림 억제 시각, 이메일 발송 큐와 재시도 상태 |
| Redis 7.4 | 최근 5분 이벤트 유지, TTL·Lua 기반 중복 방지, 같은 알림 반복 억제 |
| Kafka 4.1.2 | 이력 저장·실시간 집계·알림 판단의 독립 소비자 그룹 |

MySQL·Redis·Kafka는 Docker Compose, 모니터링 Spring Boot 앱과 Vue는 호스트에서 실행합니다. 선택 사항인 풋볼라운지 연동 앱도 호스트에서 실행합니다. 모든 노출 포트는 `127.0.0.1`에 바인딩합니다. 수신 API는 전용 키로 인증하며 조회 화면은 로컬 개발용으로 로그인 없이 제공합니다.

## 실행

필수: JDK 21, Node.js 20.19+ 또는 22.12+, npm, Python 3, Docker Desktop.

프로젝트 루트에서 최초 한 번:

```bash
./scripts/setup.sh
./scripts/infra.sh up
```

`setup.sh`는 임의의 DB 비밀번호와 수집 API 키를 담은 `.env`를 생성하고 프론트 의존성을 설치합니다. 기존 `.env`는 유지하며 수집 키가 없을 때만 추가합니다. `.env`는 Git에서 제외합니다. 생성된 MySQL 볼륨의 계정 비밀번호는 `.env` 변경만으로 바뀌지 않습니다.

### 모니터링 단독 실행

다음 두 명령은 각각 별도의 터미널에서 실행합니다.

```bash
./scripts/backend.sh
./scripts/frontend.sh
```

기동 후 프로젝트 루트에서 읽기 전용 상태 검사를 실행합니다.

```bash
python3 scripts/smoke.py
```

브라우저에서 [대시보드](http://127.0.0.1:15273)를 엽니다. 처음에는 등록된 서비스와 인프라 상태만 보이며, 수집 이벤트가 없으면 요청 차트와 이력은 비어 있습니다. 화면의 숫자를 만들기 위해 실제 서비스 데이터가 함께 배포되지는 않습니다.

### 풋볼라운지 요청 연동 — 선택 사항

풋볼라운지의 수집 모듈과 `monitoring-local` 프로필을 갖춘 별도 저장소가 있을 때만 다음 절차를 진행합니다. 연동 앱은 실제 Spring Boot 앱과 보안 필터를 사용하지만, 메모리 DB와 테스트 전용 경로로 동작합니다.

```bash
# 별도 터미널에서 실행. 본인의 풋볼라운지 저장소 경로로 변경합니다.
FOOTBALL_SERVER_DIR=/path/to/football_link/server python3 scripts/football-local.py
```

경로를 생략하면 `~/IdeaProjects/football_link/server`를 사용합니다. 자세한 내용은 [로컬 테스트 가이드](docs/local-testing.md)를 참고하세요.

앱들이 실행된 뒤 테스트 요청을 발생시킵니다.

```bash
python3 scripts/local-e2e.py
```

대시보드는 15초마다 갱신되며 일시정지할 수 있습니다. 차트는 5분·15분·1시간을 조회하고 알림 판단은 최근 5분을 사용합니다. 수신 상태는 서비스 가용성과 다릅니다. [대시보드 사용법과 지표 정의](docs/dashboard.md)를 참고하세요.

## 포트

| 서비스 | 호스트 주소 | Docker 내부 주소 |
|---|---|---|
| Vue | `127.0.0.1:15273` | 호스트 실행 |
| 모니터링 API | `127.0.0.1:18180` | 호스트 실행 |
| 풋볼라운지 로컬 | `127.0.0.1:18090` | 호스트 실행 |
| MySQL | `127.0.0.1:23306` | `mysql:3306` |
| Redis | `127.0.0.1:26379` | `redis:6379` |
| Kafka | `127.0.0.1:29092` | `kafka:29092` |

모니터링 포트는 `.env`에서 변경합니다. 풋볼라운지 테스트 포트는 스크립트에서 `18090`으로 고정합니다. Kafka는 호스트·컨테이너용 advertised listener를 분리했습니다.

모니터링 백엔드는 작업 디렉터리가 `backend`일 때 `../.env`를 읽습니다. IDE에서도 이 디렉터리를 지정하거나 환경변수를 주입하세요. Vite는 서버 측에서 포트 설정만 사용하며 수집 키와 DB 비밀번호를 브라우저에 전달하지 않습니다.

## API

| 경로 | 동작 |
|---|---|
| `POST /api/v1/ingest/http-requests` | `X-Monitor-Key` 인증, 최대 100건 배치, Kafka 확인 후 202 |
| `GET /api/v1/services` | 서비스 목록과 최근 이벤트 수신 상태 |
| `GET /api/v1/services/football-lounge/metrics` | 최근 5분 요청 수·5xx 오류율·평균 처리 시간·상위 20개 API |
| `GET /api/v1/services/football-lounge/events` | 최근 30건 요청 이력 |
| `GET /api/v1/services/football-lounge/analytics?minutes=15` | 5·15·60분 통계, 구간별 차트·P95, 상위 50개 API, 최신 100개 요청 |
| `GET /api/v1/services/football-lounge/alerts` | 알림 설정과 최근 50건 알림 이력 |
| `POST /api/v1/notifications/email/test` | 인증키 필요, 설정한 주소로 테스트 이메일 예약 |
| `GET /api/v1/notifications/email/deliveries/{id}` | 인증키 필요, 개별 이메일 처리 상태 |
| `GET /actuator/health/infrastructure` | MySQL·Redis·Kafka 연결 확인 |

조회 API의 `environment` 기본값은 `development`입니다. 수신 본문과 장애 처리 정책은 [구조 설명](docs/architecture.md)에 있습니다.

## 오류율·응답 시간 알림

기본값은 최근 5분에 요청 10건 이상일 때 **5xx 오류율 10% 이상** 또는 **평균 처리 시간 500ms 이상**입니다. 서비스·환경·알림 종류마다 10분 동안 같은 알림을 억제합니다. 이후 새로운 요청에서도 기준을 충족하면 다시 알립니다. 회복 시 억제 시간을 즉시 해제하지는 않습니다.

대시보드가 열려 있으면 15초 폴링으로 새 알림 배너를 표시합니다. Gmail SMTP를 설정하면 대시보드를 닫아도 이메일이 발송됩니다. Redis로 억제된 알림은 이메일 작업도 생성하지 않습니다. 설정·재현 방법은 [알림 가이드](docs/alerts.md)와 [이메일 가이드](docs/email.md)를 참고하세요.

이메일은 알림과 함께 MySQL 발송 큐에 저장한 뒤 별도 작업자가 보냅니다. 실패하면 기본 30·60·120·240초 간격으로 최대 5번 시도합니다. 화면에서는 발송 대기·재시도·실패·SMTP 접수 상태를 확인할 수 있습니다. 로컬 PC와 모니터링 백엔드는 계속 실행되어 있어야 합니다.

## 검증과 종료

```bash
# 프로젝트 루트에서 실행. 모니터링 소스만으로 가능한 단위 테스트와 빌드
(cd backend && ./gradlew test bootJar)
(cd frontend && npm run build)

# 모니터링 서버와 인프라가 실행 중일 때
python3 scripts/smoke.py
python3 scripts/analytics-e2e.py
python3 scripts/alerts-e2e.py

# 별도 풋볼라운지 테스트 앱까지 실행 중일 때
python3 scripts/local-e2e.py

# 이 프로젝트의 Redis만 잠시 중지했다 복구하는 장애 실험
python3 scripts/redis-recovery.py

./scripts/infra.sh status
./scripts/infra.sh logs kafka
./scripts/infra.sh down
```

앱은 각 터미널에서 `Ctrl+C`로 종료합니다. `infra.sh down`은 볼륨을 보존합니다. 데이터 삭제를 의도할 때만 `docker compose down -v`를 사용하세요.

이메일이 켜진 상태에서는 `alerts-e2e.py`가 외부 테스트 메일 발송을 막기 위해 중단합니다. 메일 검증은 기존 백엔드를 종료한 다음 `python3 scripts/email-e2e.py`로 진행하세요. 이 스크립트는 Gmail 설정을 사용하지 않는 로컬 SMTP 서버와 임시 백엔드를 실행하며, 종료 후에는 `./scripts/backend.sh`로 평소 백엔드를 다시 실행합니다.

2026-10-14 검증: 모니터링 단위 테스트 28개(이메일 12개 포함), 분석 API의 실제 MySQL 통합 검증, Vue 타입 검사·빌드 통과. 로컬 SMTP 통합 검사에서 실패 재시도·재시작 복구·중복 억제를 확인했습니다. 풋볼라운지의 실제 HTTP 60건으로 Gmail 알림 2건을 발송했고 사용자가 각각 1건 수신·중복 없음을 확인했습니다. [실제 HTTP 알림 검증과 재현](docs/live-alert-test.md), [대시보드 가이드](docs/dashboard.md)에 정리했습니다.

## 현재 범위와 다음 단계

수집·이력·최근 5분 지표·최대 1시간 시계열/P95·요청 탐색·대시보드 알림·Gmail 이메일·반복 알림 억제를 구현했습니다. 장기 통계, 운영 배포와 런팡 연결은 아직 구현하지 않았습니다. 풋볼라운지 수집기는 기본 비활성화이며 로컬 프로필에서 활성화합니다.

Kafka가 받아들이기 전의 수집 버퍼는 메모리에 있으므로 장애가 길어지면 일부 지표가 유실될 수 있습니다. Redis 구현은 최근 이벤트를 읽어 서버에서 통계를 계산하는 소규모 버전입니다. 높은 트래픽에는 시간 버킷 집계와 메모리 상한 설계가 필요합니다.

## 공개 저장소와 보안

- 실제 `.env`, 실행 로그·PID 파일(`.runtime/`), DB 데이터, 빌드 결과와 의존성 폴더는 커밋 대상에서 제외합니다.
- `.env.example`은 예제 설정만 제공합니다. `setup.sh`는 로컬 DB 비밀번호와 수집 API 키를 새로 생성하며, 기본 이메일 발송은 비활성화합니다.
- 저장소 공개와 모니터링 서버의 인터넷 공개는 다릅니다. 현재 조회 API와 대시보드에는 로그인 기능이 없으므로 그대로 외부에 노출하지 마세요.
- 운영 배포 전에는 조회 인증·권한, TLS, 이력 보존 정책과 용량·부하 검증이 필요합니다.
