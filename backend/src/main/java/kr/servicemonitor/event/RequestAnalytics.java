package kr.servicemonitor.event;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Bounded time ranges, exact nearest-rank P95, and zero-filled request buckets. */
@Repository
public class RequestAnalytics {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public RequestAnalytics(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    private static final String FILTER = " FROM http_request_event WHERE service_key = ? AND environment = ? AND occurred_at >= ? AND occurred_at < ? ";
    private static final String AGGREGATES = """
            COUNT(*) AS requests, COALESCE(SUM(status_code >= 500), 0) AS errors,
            AVG(duration_ms) AS average_ms, MAX(duration_ms) AS max_ms,
            MAX(CASE WHEN position = CEIL(samples * 0.95) THEN duration_ms END) AS p95_ms
            """;

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Response query(String serviceKey, String environment, int minutes) {
        int step = bucketSeconds(minutes);
        Instant end = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant start = end.minusSeconds(minutes * 60L);
        Object[] args = {serviceKey, environment, Timestamp.from(start), Timestamp.from(end)};
        Summary summary = jdbc.queryForObject("""
                WITH ranked AS (SELECT status_code, duration_ms,
                ROW_NUMBER() OVER (ORDER BY duration_ms, event_id) AS position,
                COUNT(*) OVER () AS samples
                """ + FILTER + ") SELECT " + AGGREGATES + " FROM ranked", (rs, row) -> summary(rs), args);

        List<BucketRow> measured = jdbc.query("""
                WITH source AS (SELECT event_id, status_code, duration_ms,
                FLOOR(TIMESTAMPDIFF(MICROSECOND, ?, occurred_at) / ?) AS bucket
                """ + FILTER + """
                ), ranked AS (SELECT *,
                ROW_NUMBER() OVER (PARTITION BY bucket ORDER BY duration_ms, event_id) AS position,
                COUNT(*) OVER (PARTITION BY bucket) AS samples FROM source)
                SELECT bucket,
                """ + AGGREGATES + " FROM ranked GROUP BY bucket ORDER BY bucket",
                (rs, row) -> new BucketRow(rs.getInt("bucket"), summary(rs)),
                Timestamp.from(start), step * 1_000_000L, serviceKey, environment, Timestamp.from(start), Timestamp.from(end));
        Map<Integer, Summary> buckets = new HashMap<>();
        measured.forEach(row -> buckets.put(row.index(), row.summary()));

        List<Route> routes = jdbc.query("""
                WITH ranked AS (SELECT method, route, status_code, duration_ms,
                ROW_NUMBER() OVER (PARTITION BY method, route ORDER BY duration_ms, event_id) AS position,
                COUNT(*) OVER (PARTITION BY method, route) AS samples
                """ + FILTER + ") SELECT method, route, " + AGGREGATES
                + " FROM ranked GROUP BY method, route ORDER BY requests DESC, method, route LIMIT 50",
                (rs, row) -> new Route(rs.getString("method"), rs.getString("route"), summary(rs)), args);
        List<RequestHistory.RecentEvent> events = jdbc.query("""
                SELECT event_id, method, route, status_code, duration_ms, occurred_at, deployment_version
                """ + FILTER + " ORDER BY occurred_at DESC, event_id DESC LIMIT 100",
                (rs, row) -> new RequestHistory.RecentEvent(rs.getString("event_id"), rs.getString("method"),
                        rs.getString("route"), rs.getInt("status_code"), rs.getLong("duration_ms"),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getString("deployment_version")), args);
        return new Response(minutes, step, start, end, summary, fillBuckets(start, minutes, buckets), routes, events);
    }

    static int bucketSeconds(int minutes) {
        return switch (minutes) { case 5 -> 10; case 15 -> 30; case 60 -> 120;
            default -> throw new IllegalArgumentException("Supported ranges: 5, 15, 60 minutes"); };
    }

    static List<Bucket> fillBuckets(Instant start, int minutes, Map<Integer, Summary> measured) {
        int step = bucketSeconds(minutes);
        List<Bucket> result = new ArrayList<>();
        for (int i = 0; i < minutes * 60 / step; i++)
            result.add(new Bucket(start.plusSeconds((long) i * step), measured.getOrDefault(i, Summary.empty())));
        return List.copyOf(result);
    }

    private static Summary summary(ResultSet rs) throws SQLException {
        long count = rs.getLong("requests"), errors = rs.getLong("errors");
        return new Summary(count, errors, count == 0 ? null : errors * 100.0 / count,
                number(rs, "average_ms"), number(rs, "p95_ms"), number(rs, "max_ms"));
    }
    private static Double number(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column); return rs.wasNull() ? null : value;
    }

    public record Summary(long requestCount, long serverErrorCount, Double serverErrorRate,
                          Double averageDurationMs, Double p95DurationMs, Double maxDurationMs) {
        static Summary empty() { return new Summary(0, 0, null, null, null, null); }
    }
    private record BucketRow(int index, Summary summary) {}
    public record Bucket(Instant startedAt, Summary metrics) {}
    public record Route(String method, String route, Summary metrics) {}
    public record Response(int windowMinutes, int bucketSeconds, Instant from, Instant asOf, Summary summary,
                           List<Bucket> buckets, List<Route> routes, List<RequestHistory.RecentEvent> events) {}
}
