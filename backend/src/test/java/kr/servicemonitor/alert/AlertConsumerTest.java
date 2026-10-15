package kr.servicemonitor.alert;

import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import kr.servicemonitor.event.LiveRequestStore;
import kr.servicemonitor.event.RequestEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AlertConsumerTest {
    private final Instant now = Instant.parse("2026-10-14T12:00:00Z");
    private final ObjectMapper mapper = mock(ObjectMapper.class);
    private final LiveRequestStore store = mock(LiveRequestStore.class);
    private final AlertDelivery delivery = mock(AlertDelivery.class);
    private final AlertConsumer consumer = new AlertConsumer(mapper, store, new AlertPolicy(true, 1, 10, 500, 600), delivery, Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void ignoresReplayOutsideCurrentWindow() {
        when(mapper.readValue("old", RequestEvent.class)).thenReturn(event(now.minusSeconds(300)));
        consumer.consume("old");
        verifyNoInteractions(store, delivery);
    }

    @Test
    void evaluatesItsOwnWindowAfterRecordingTheEvent() {
        var event = event(now);
        when(mapper.readValue("fresh", RequestEvent.class)).thenReturn(event);
        when(store.recent("football-lounge", "development", now, "alerts")).thenReturn(List.of(event));
        consumer.consume("fresh");
        var order = inOrder(store, delivery);
        order.verify(store).record(event, "alerts");
        order.verify(store).recent("football-lounge", "development", now, "alerts");
        order.verify(delivery).deliver(eq(event), argThat(b -> b.type() == AlertPolicy.Type.ERROR_RATE), any());
        order.verify(delivery).deliver(eq(event), argThat(b -> b.type() == AlertPolicy.Type.LATENCY), any());
    }

    private RequestEvent event(Instant occurred) {
        return new RequestEvent(UUID.randomUUID(), "football-lounge", "development", "GET", "/test", 500, 900, occurred, "test");
    }
}
