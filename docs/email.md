# Gmail 이메일 알림

알림 생성 시점에 이메일 채널이 켜져 있으면 오류율·응답 시간 알림을 Gmail SMTP로 보냅니다. 대시보드가 닫혀 있어도 발송합니다. 로컬 PC와 백엔드가 꺼져 있으면 발송할 수 없으며, 이미 큐에 저장한 작업은 다시 실행했을 때 이어서 처리합니다. 이메일을 켜기 전에 생성된 과거 알림을 소급 발송하지 않습니다.

## 계정 설정

루트 `.env`에 아래 값을 설정합니다. 실제 계정과 비밀번호는 이 문서나 소스에 넣지 않습니다. `.env`는 Git 제외 대상이며 현재 파일 권한은 소유자 읽기·쓰기입니다.

```dotenv
MONITOR_EMAIL_ENABLED=true
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=your-account@gmail.com
SMTP_PASSWORD=your-google-app-password
SMTP_AUTH=true
SMTP_STARTTLS_ENABLED=true
SMTP_SSL_ENABLED=false
MONITOR_EMAIL_FROM=your-account@gmail.com
MONITOR_EMAIL_TO=your-recipient@example.com
MONITOR_EMAIL_MAX_ATTEMPTS=5
MONITOR_EMAIL_RETRY_DELAY_SECONDS=30
```

이 구현은 Gmail 앱 비밀번호 인증을 사용합니다. 앱 비밀번호 발급에는 2단계 인증이 필요하며, 발급 화면에서 받은 값을 공백 없이 넣습니다. [Google 앱 비밀번호 안내](https://support.google.com/accounts/answer/185833?hl=ko)

변경 후 백엔드를 재시작합니다. Gmail 연결에는 STARTTLS가 필수이며 서버 인증서를 확인합니다. 연결·읽기·쓰기 제한은 각각 5초입니다. SMTP 인증 정보와 원시 오류 응답을 화면이나 발송 실패 로그에 노출하지 않습니다. 수신 주소는 화면에서 마스킹합니다.

## 실제 메일 테스트

백엔드 실행 후 프로젝트 루트에서:

```bash
python3 scripts/email-test.py
```

설정된 수신 주소로 **실제 이메일 한 통**을 예약합니다. 제목은 `[Service Monitor] 이메일 연동 테스트`이며 실제 장애 알림과 구분됩니다. 스크립트는 최대 45초 동안 SMTP 접수 상태를 확인합니다. 지연되면 출력된 요청 ID로 같은 작업을 다시 조회할 수 있습니다.

```bash
python3 scripts/email-test.py --request-id <출력된-요청-ID>
```

같은 요청 ID로는 추가 메일 작업이 생성되지 않습니다. 이미 `FAILED`인 작업의 시도 횟수를 초기화하는 명령은 아닙니다. 자격 증명 문제를 수정한 뒤 새 테스트를 보내려면 요청 ID를 생략하세요.

API는 `X-Monitor-Key`가 필요합니다. 수신자는 `.env`의 고정 주소이며 요청 본문으로 임의의 수신 주소를 지정할 수 없습니다.

- `POST /api/v1/notifications/email/test`, 본문 `{"requestId":"UUID"}` → `deliveryId`.
- `GET /api/v1/notifications/email/deliveries/{deliveryId}` → 처리 상태와 안전한 오류 코드.

## 상태와 재시도

| 상태 | 의미 |
|---|---|
| PENDING | DB 발송 큐에 저장됨 |
| SENDING | 작업자가 확보해 전송 중 |
| RETRY | 다음 전송 시각까지 대기 |
| SENT | SMTP 서버가 메일을 정상 접수함 |
| FAILED | 최대 횟수에 도달해 자동 시도 종료 |

기본 작업 주기는 5초이며, 실패 후 30·60·120·240초 간격으로 재시도해 총 5번까지 시도합니다. 최대 횟수는 최초 시도를 포함합니다. 연결 오류, 인증 실패 등은 `SMTP_SEND`, `SMTP_AUTH`처럼 안전한 코드로 저장합니다. 실제 알림 행마다 상태·시도 횟수·다음 시도를 표시합니다. 과거 이메일 미설정 시점의 알림에는 발송 작업이 없습니다.

알림과 이메일 큐의 삽입은 같은 DB 트랜잭션입니다. 알림당 이메일 작업은 고유 키로 한 건만 생성합니다. Redis로 반복 억제된 알림은 이메일 큐도 추가하지 않습니다. 발신자·수신자·내용은 작업 생성 시 저장하므로 `.env` 변경으로 기존 큐의 목적지가 바뀌지 않습니다. 이메일을 끄면 새 작업 생성과 기존 작업 처리가 멈추고, 켜면 미완료 작업이 재개됩니다.

여러 작업자가 동시에 같은 행을 가져가지 않도록 행 잠금과 `SKIP LOCKED`를 사용합니다. 전송 전에 확보한 작업에는 5분 임대와 토큰을 기록하며, 서버가 중단되면 만료 뒤 다른 작업자가 이어받을 수 있습니다. SMTP 호출은 DB 트랜잭션 밖에서 실행합니다.

SMTP가 접수한 직후 응답을 잃거나 DB 상태 저장 전에 프로세스가 종료되면 재시도로 같은 메일이 다시 도착할 가능성은 있습니다. 일반 SMTP에서 수신함까지 정확히 한 번 전달을 보장하지 않습니다. `SENT`는 수신함·스팸함 도착을 직접 확인한 상태가 아닙니다. 메시지에는 추적용 `X-Monitor-Delivery-ID` 헤더가 포함됩니다.

## 외부 발송 없는 통합 검사

평소 모니터링 백엔드를 종료하고 다음을 실행합니다. MySQL·Redis·Kafka는 계속 실행합니다.

```bash
(cd backend && ./gradlew test bootJar)
python3 scripts/email-e2e.py
```

Gmail 설정을 명시적으로 덮어써 로컬 SMTP 수신 서버와 테스트 백엔드만 사용합니다. 실제 수신자가 있는 미완료 작업이 남아 있으면 시작하지 않습니다. 이 테스트 동안 일반 서비스 트래픽을 발생시키지 마세요. 검증 후 테스트 프로세스와 해당 실행에서 만든 DB·Redis 데이터를 정리합니다. 평소 백엔드는 `./scripts/backend.sh`로 다시 시작합니다.

검증 범위: Kafka 기준치 초과→이메일 작업 생성, 임시 SMTP 실패 후 재시도, 반복 알림의 메일 억제, 백엔드 재시작 후 미완료 작업 재개, 같은 테스트 요청의 중복 방지, 최대 횟수 종료, 임대 만료 복구, UTF-8 한글 내용.

2026-10-14: 백엔드 단위 테스트 26개와 Vue 타입 검사·빌드 통과. 위 로컬 SMTP 통합 검사 통과. 실제 Gmail SMTP 연동 테스트는 서버 접수와 사용자의 수신 확인까지 완료했습니다.
