package kr.servicemonitor.alert;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import kr.servicemonitor.event.RequestEvent;
import kr.servicemonitor.event.RequestMetrics;
import org.junit.jupiter.api.Test;

class AlertDeliveryTest {
    private final AlertCooldown cooldown = mock(AlertCooldown.class);
    private final AlertRepository repository = mock(AlertRepository.class);
    private final AlertDelivery delivery = new AlertDelivery(cooldown, repository, new AlertPolicy(true, 10, 10, 500, 600));
    private final Instant now = Instant.parse("2026-10-14T12:00:00Z");
    private final RequestEvent event = new RequestEvent(UUID.randomUUID(), "football-lounge", "development", "GET", "/test", 500, 900, now, "test");
    private final AlertPolicy.Breach breach = new AlertPolicy.Breach(AlertPolicy.Type.ERROR_RATE, 20, 10);
    private final RequestMetrics.Summary summary = new RequestMetrics.Summary(300, now, 10, 2, 20.0, 900.0, List.of());

    @Test
    void redisSuppressionAvoidsDatabaseWrites() {
        when(cooldown.active("football-lounge", "development", breach.type())).thenReturn(true);
        delivery.deliver(event, breach, summary);
        verifyNoInteractions(repository);
        verify(cooldown, never()).remember(anyString(), anyString(), any(), any());
    }

    @Test
    void failureToSaveDoesNotStartCooldownAndCanRetry() {
        when(repository.saveIfDue(event, breach, summary, 600)).thenThrow(new IllegalStateException("database unavailable")).thenReturn(now);
        assertThrows(IllegalStateException.class, () -> delivery.deliver(event, breach, summary));
        verify(cooldown, never()).remember(anyString(), anyString(), any(), any());
        delivery.deliver(event, breach, summary);
        verify(cooldown).remember("football-lounge", "development", breach.type(), now.plusSeconds(600));
    }

    @Test
    void recoveredCooldownUsesOriginalNotificationTime() {
        when(repository.saveIfDue(event, breach, summary, 600)).thenReturn(now.minusSeconds(120));
        delivery.deliver(event, breach, summary);
        var order = inOrder(repository, cooldown);
        order.verify(repository).saveIfDue(event, breach, summary, 600);
        order.verify(cooldown).remember("football-lounge", "development", breach.type(), now.plusSeconds(480));
    }

    @Test
    void redisFailureAfterCommitPropagatesForKafkaRetry() {
        when(repository.saveIfDue(event, breach, summary, 600)).thenReturn(now);
        doThrow(new IllegalStateException("redis unavailable")).when(cooldown).remember(anyString(), anyString(), any(), any());
        assertThrows(IllegalStateException.class, () -> delivery.deliver(event, breach, summary));
        verify(repository).saveIfDue(event, breach, summary, 600);
    }
}
