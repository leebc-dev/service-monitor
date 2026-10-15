package kr.servicemonitor.email;

import java.time.Instant;
import java.util.Locale;
import kr.servicemonitor.alert.AlertPolicy.Breach;
import kr.servicemonitor.alert.AlertPolicy.Type;
import kr.servicemonitor.event.RequestEvent;
import kr.servicemonitor.event.RequestMetrics.Summary;

public record EmailContent(String subject, String body) {
    public static EmailContent alert(String alertId, RequestEvent event, Breach breach, Summary summary,
                                     Instant createdAt, String dashboardUrl) {
        String title = breach.type() == Type.ERROR_RATE ? "서버 오류율 경고" : "응답 시간 경고";
        String unit = breach.type() == Type.ERROR_RATE ? "%" : " ms";
        String subject = "[Service Monitor][" + event.environment() + "] " + event.serviceKey() + " · " + title;
        String body = String.format(Locale.ROOT, """
                %s

                서비스: %s
                환경: %s
                관측값: %.2f%s
                기준: %.2f%s 이상
                판단 구간: 최근 %d초, 요청 %d건
                판단 시각(UTC): %s
                알림 생성 시각(UTC): %s
                알림 ID: %s

                대시보드: %s

                발생 당시의 지표입니다. 현재 상태와 다를 수 있습니다.
                127.0.0.1 주소는 모니터링을 실행한 PC에서만 열립니다.
                """, title, event.serviceKey(), event.environment(), breach.observedValue(), unit,
                breach.thresholdValue(), unit, summary.windowSeconds(), summary.requestCount(),
                summary.asOf(), createdAt, alertId, dashboardUrl);
        return new EmailContent(subject, body);
    }

    public static EmailContent test(String id, String dashboardUrl) {
        return new EmailContent("[Service Monitor] 이메일 연동 테스트",
                "Service Monitor의 이메일 연동 테스트입니다. 실제 장애 알림이 아닙니다.\n\n"
                + "테스트 ID: " + id + "\n대시보드: " + dashboardUrl + "\n"
                + "이후 오류율·응답 시간 기준을 충족하면 이 주소로 알림을 보냅니다.\n");
    }
}
