package kr.servicemonitor.event;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequestAnalyticsTest {
    @Test
    void fillsMissingBucketsWithoutInventingLatencyOrErrorRate() {
        var start = Instant.parse("2026-10-14T10:00:00Z");
        var measured = new RequestAnalytics.Summary(10, 2, 20.0, 300.0, 900.0, 900.0);
        var buckets = RequestAnalytics.fillBuckets(start, 5, Map.of(1, measured));
        assertEquals(30, buckets.size());
        assertEquals(0, buckets.getFirst().metrics().requestCount());
        assertNull(buckets.getFirst().metrics().averageDurationMs());
        assertNull(buckets.getFirst().metrics().serverErrorRate());
        assertEquals(start.plusSeconds(10), buckets.get(1).startedAt());
        assertEquals(measured, buckets.get(1).metrics());
        assertEquals(start.plusSeconds(290), buckets.getLast().startedAt());
    }

    @Test
    void boundsQueriesToSupportedTimeRanges() {
        assertEquals(10, RequestAnalytics.bucketSeconds(5));
        assertEquals(30, RequestAnalytics.bucketSeconds(15));
        assertEquals(120, RequestAnalytics.bucketSeconds(60));
        assertThrows(IllegalArgumentException.class, () -> RequestAnalytics.bucketSeconds(1440));
    }
}
