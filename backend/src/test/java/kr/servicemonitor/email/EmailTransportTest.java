package kr.servicemonitor.email;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import kr.servicemonitor.alert.AlertPolicy;
import kr.servicemonitor.event.RequestEvent;
import kr.servicemonitor.event.RequestMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

class EmailTransportTest {
    @Test
    void producesUtf8TextWithAlertContextAndDeliveryId() throws Exception {
        var now = Instant.parse("2026-10-14T12:00:00Z");
        var event = new RequestEvent(UUID.randomUUID(), "football-lounge", "development", "GET", "/api/test", 500, 900, now, "test");
        var breach = new AlertPolicy.Breach(AlertPolicy.Type.ERROR_RATE, 20, 10);
        var summary = new RequestMetrics.Summary(300, now, 10, 2, 20.0, 900.0, List.of());
        var content = EmailContent.alert("test-id", event, breach, summary, now, "http://127.0.0.1:15273/#alerts");
        var message = new MimeMessage(Session.getInstance(new Properties()));
        var sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(message);
        new EmailTransport(sender).send(new EmailOutbox.Delivery("test-id", "token", 1, "sender@example.test", "recipient@example.test", content.subject(), content.body()));
        message.saveChanges();
        assertTrue(message.getSubject().contains("서버 오류율 경고"));
        assertTrue(message.getContent().toString().contains("관측값: 20.00%"));
        assertTrue(message.getContent().toString().contains("기준: 10.00% 이상"));
        assertTrue(message.getContent().toString().contains("요청 10건"));
        assertEquals("test-id", message.getHeader("X-Monitor-Delivery-ID", null));
        assertEquals("recipient@example.test", message.getAllRecipients()[0].toString());
        verify(sender).send(message);
    }

    @Test
    void connectionTestIsClearlyDifferentFromAnIncident() {
        var content = EmailContent.test("test-id", "http://127.0.0.1:15273/#alerts");
        assertTrue(content.subject().contains("연동 테스트"));
        assertTrue(content.body().contains("실제 장애 알림이 아닙니다"));
    }
}
