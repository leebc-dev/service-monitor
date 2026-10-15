package kr.servicemonitor.event;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestMetricsTest {
    @Test
    void separatesServerErrorsFromClientErrorsAndAveragesDurations() {
        var summary = RequestMetrics.summarize(List.of(event(200, 10), event(401, 20), event(500, 300)), Instant.now());
        assertEquals(3, summary.requestCount());
        assertEquals(1, summary.serverErrorCount());
        assertEquals(100.0 / 3, summary.serverErrorRate(), 0.001);
        assertEquals(110.0, summary.averageDurationMs());
        assertEquals(1, summary.routes().size());
    }

    @Test
    void emptyWindowDoesNotClaimZeroLatencyOrErrorRate() {
        var summary = RequestMetrics.summarize(List.of(), Instant.now());
        assertEquals(0, summary.requestCount());
        assertNull(summary.averageDurationMs());
        assertNull(summary.serverErrorRate());
    }

    private RequestEvent event(int status, long duration) {
        return new RequestEvent(UUID.randomUUID(), "football-lounge", "development", "GET", "/api/items/{id}",
                status, duration, Instant.now(), "test");
    }
}
