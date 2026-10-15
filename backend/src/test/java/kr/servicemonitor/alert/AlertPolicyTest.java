package kr.servicemonitor.alert;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import kr.servicemonitor.event.RequestEvent;
import kr.servicemonitor.event.RequestMetrics;
import org.junit.jupiter.api.Test;

class AlertPolicyTest {
    private final AlertPolicy policy = new AlertPolicy(true, 10, 10, 500, 600);

    @Test
    void waitsForMinimumSampleEvenWhenEveryRequestFails() {
        assertTrue(policy.evaluate(summary(9, 9, 2000)).isEmpty());
        assertTrue(policy.evaluate(summary(0, 0, 0)).isEmpty());
    }

    @Test
    void exactThresholdsTriggerBothIndependentRules() {
        var result = policy.evaluate(summary(10, 1, 500));
        assertEquals(List.of(AlertPolicy.Type.ERROR_RATE, AlertPolicy.Type.LATENCY), result.stream().map(AlertPolicy.Breach::type).toList());
        assertEquals(10.0, result.getFirst().observedValue());
    }

    @Test
    void belowThresholdAndDisabledPolicyProduceNoAlert() {
        assertTrue(policy.evaluate(summary(11, 1, 499)).isEmpty());
        assertTrue(new AlertPolicy(false, 10, 10, 500, 600).evaluate(summary(10, 10, 2000)).isEmpty());
    }

    @Test
    void clientErrorsAreNotServerErrors() {
        var events = IntStream.range(0, 10).mapToObj(i -> event(401, 20)).toList();
        assertTrue(policy.evaluate(RequestMetrics.summarize(events, Instant.now())).isEmpty());
    }

    private RequestMetrics.Summary summary(int count, int errors, long duration) {
        return RequestMetrics.summarize(IntStream.range(0, count).mapToObj(i -> event(i < errors ? 500 : 200, duration)).toList(), Instant.now());
    }

    private RequestEvent event(int status, long duration) {
        return new RequestEvent(UUID.randomUUID(), "football-lounge", "development", "GET", "/test", status, duration, Instant.now(), "test");
    }
}
