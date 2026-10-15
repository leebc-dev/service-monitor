package kr.servicemonitor.event;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RequestHistory {
    private final JdbcTemplate jdbc;
    public RequestHistory(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void save(RequestEvent event) {
        jdbc.update("""
                INSERT INTO http_request_event
                (event_id, service_key, environment, method, route, status_code, duration_ms, occurred_at, deployment_version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE event_id = event_id
                """, event.eventId().toString(), event.serviceKey(), event.environment(), event.method(),
                event.route(), event.statusCode(), event.durationMs(), Timestamp.from(event.occurredAt()), event.deploymentVersion());
    }

    public Instant lastReceived(String serviceKey, String environment) {
        Timestamp timestamp = jdbc.queryForObject("SELECT MAX(received_at) FROM http_request_event WHERE service_key = ? AND environment = ?",
                Timestamp.class, serviceKey, environment);
        return timestamp == null ? null : timestamp.toInstant();
    }

    public List<RecentEvent> recent(String serviceKey, String environment) {
        return jdbc.query("""
                SELECT event_id, method, route, status_code, duration_ms, occurred_at, deployment_version
                FROM http_request_event WHERE service_key = ? AND environment = ?
                ORDER BY occurred_at DESC, event_id DESC LIMIT 30
                """, (rs, row) -> new RecentEvent(rs.getString("event_id"), rs.getString("method"),
                rs.getString("route"), rs.getInt("status_code"), rs.getLong("duration_ms"),
                rs.getTimestamp("occurred_at").toInstant(), rs.getString("deployment_version")), serviceKey, environment);
    }

    public record RecentEvent(String eventId, String method, String route, int statusCode,
                              long durationMs, Instant occurredAt, String deploymentVersion) {}
}
