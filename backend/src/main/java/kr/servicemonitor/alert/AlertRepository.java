package kr.servicemonitor.alert;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kr.servicemonitor.alert.AlertPolicy.Breach;
import kr.servicemonitor.event.RequestEvent;
import kr.servicemonitor.event.RequestMetrics.Summary;
import kr.servicemonitor.email.EmailOutbox;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class AlertRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final EmailOutbox email;

    public AlertRepository(JdbcTemplate jdbc, TransactionTemplate transaction, Clock clock, EmailOutbox email) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.clock = clock;
        this.email = email;
    }

    // This durable check closes the gap between a committed notification and its Redis cooldown.
    // Concurrent workers, a crash after commit, or Redis key loss must not create another alert.
    public Instant saveIfDue(RequestEvent event, Breach breach, Summary summary, long cooldownSeconds) {
        String id = UUID.nameUUIDFromBytes((event.serviceKey() + ":" + event.environment() + ":"
                + breach.type() + ":" + event.eventId()).getBytes(StandardCharsets.UTF_8)).toString();
        return Objects.requireNonNull(transaction.execute(status -> {
            jdbc.update("""
                    INSERT INTO alert_rule_state (service_key, environment, rule_type)
                    VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE rule_type = rule_type
                    """, event.serviceKey(), event.environment(), breach.type().name());
            Timestamp last = jdbc.queryForObject("""
                    SELECT last_notified_at FROM alert_rule_state
                    WHERE service_key = ? AND environment = ? AND rule_type = ? FOR UPDATE
                    """, Timestamp.class, event.serviceKey(), event.environment(), breach.type().name());
            Instant now = clock.instant();
            if (last != null && now.isBefore(last.toInstant().plusSeconds(cooldownSeconds))) return last.toInstant();
            Timestamp existing = jdbc.queryForObject("SELECT MAX(created_at) FROM alert_notification WHERE alert_id = ?",
                    Timestamp.class, id);
            if (existing != null) return last == null ? existing.toInstant() : last.toInstant();
            jdbc.update("""
                    INSERT INTO alert_notification
                    (alert_id, service_key, environment, rule_type, observed_value, threshold_value,
                     request_count, window_seconds, window_ended_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, event.serviceKey(), event.environment(), breach.type().name(), breach.observedValue(),
                    breach.thresholdValue(), summary.requestCount(), summary.windowSeconds(),
                    Timestamp.from(summary.asOf()), Timestamp.from(now));
            email.enqueueAlert(id, event, breach, summary, now);
            jdbc.update("""
                    UPDATE alert_rule_state SET last_notified_at = ?
                    WHERE service_key = ? AND environment = ? AND rule_type = ?
                    """, Timestamp.from(now), event.serviceKey(), event.environment(), breach.type().name());
            return now;
        }));
    }

    public List<Notification> recent(String serviceKey, String environment) {
        return jdbc.query("""
                SELECT a.*, e.status AS email_status, e.attempt_count, e.next_attempt_at, e.sent_at, e.last_error_code
                FROM alert_notification a LEFT JOIN email_outbox e ON a.alert_id = e.alert_id
                WHERE a.service_key = ? AND a.environment = ?
                ORDER BY a.created_at DESC, a.alert_id DESC LIMIT 50
                """, (rs, row) -> new Notification(rs.getString("alert_id"), rs.getString("rule_type"),
                rs.getDouble("observed_value"), rs.getDouble("threshold_value"), rs.getLong("request_count"),
                rs.getInt("window_seconds"), rs.getTimestamp("window_ended_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("email_status") == null ? null : new EmailOutbox.Status(rs.getString("email_status"),
                        rs.getInt("attempt_count"), rs.getTimestamp("next_attempt_at").toInstant(),
                        rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toInstant(),
                        rs.getString("last_error_code"))), serviceKey, environment);
    }

    public record Notification(String alertId, String type, double observedValue, double thresholdValue,
                               long requestCount, int windowSeconds, Instant windowEndedAt, Instant createdAt, EmailOutbox.Status email) {}
}
