package kr.servicemonitor.email;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.servicemonitor.alert.AlertPolicy.Breach;
import kr.servicemonitor.event.RequestEvent;
import kr.servicemonitor.event.RequestMetrics.Summary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class EmailOutbox {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final EmailSettings settings;
    public EmailOutbox(JdbcTemplate jdbc, TransactionTemplate transaction, Clock clock, EmailSettings settings) {
        this.jdbc = jdbc; this.transaction = transaction; this.clock = clock; this.settings = settings;
    }

    // Called inside the alert transaction: either both the alert and its email job commit, or neither does.
    public void enqueueAlert(String alertId, RequestEvent event, Breach breach, Summary summary, Instant createdAt) {
        if (!settings.enabled()) return;
        insert(alertId, alertId, EmailContent.alert(alertId, event, breach, summary, createdAt, settings.dashboardUrl()), createdAt);
    }

    public String enqueueTest(UUID requestId) {
        if (!settings.enabled()) throw new IllegalStateException("Email disabled");
        String id = UUID.nameUUIDFromBytes(("email-test:" + requestId).getBytes(StandardCharsets.UTF_8)).toString();
        insert(id, null, EmailContent.test(id, settings.dashboardUrl()), clock.instant());
        return id;
    }

    private void insert(String id, String alertId, EmailContent content, Instant now) {
        jdbc.update("""
                INSERT INTO email_outbox
                (delivery_id, alert_id, sender, recipient, subject, body, next_attempt_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE delivery_id = delivery_id
                """, id, alertId, settings.from(), settings.to(), content.subject(), content.body(), Timestamp.from(now), Timestamp.from(now));
    }

    public Optional<Delivery> claim() {
        return transaction.execute(status -> {
            Instant now = clock.instant();
            jdbc.update("""
                    UPDATE email_outbox SET status = 'FAILED', lock_token = NULL, locked_until = NULL,
                      last_error_code = 'WORKER_INTERRUPTED'
                    WHERE status = 'SENDING' AND locked_until <= ? AND attempt_count >= ?
                    """, Timestamp.from(now), settings.maxAttempts());
            List<Delivery> rows = jdbc.query("""
                    SELECT delivery_id, sender, recipient, subject, body, attempt_count
                    FROM email_outbox
                    WHERE attempt_count < ? AND
                      ((status IN ('PENDING', 'RETRY') AND next_attempt_at <= ?)
                       OR (status = 'SENDING' AND locked_until <= ?))
                    ORDER BY created_at, delivery_id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, (rs, row) -> new Delivery(rs.getString("delivery_id"), UUID.randomUUID().toString(),
                    rs.getInt("attempt_count") + 1, rs.getString("sender"), rs.getString("recipient"),
                    rs.getString("subject"), rs.getString("body")), settings.maxAttempts(), Timestamp.from(now), Timestamp.from(now));
            if (rows.isEmpty()) return Optional.empty();
            Delivery delivery = rows.getFirst();
            jdbc.update("""
                    UPDATE email_outbox SET status = 'SENDING', attempt_count = ?, lock_token = ?, locked_until = ?
                    WHERE delivery_id = ?
                    """, delivery.attempt(), delivery.lockToken(), Timestamp.from(now.plusSeconds(300)), delivery.id());
            return Optional.of(delivery);
        });
    }

    public void sent(Delivery delivery) {
        jdbc.update("""
                UPDATE email_outbox SET status = 'SENT', sent_at = ?, last_error_code = NULL, lock_token = NULL, locked_until = NULL
                WHERE delivery_id = ? AND status = 'SENDING' AND lock_token = ?
                """, Timestamp.from(clock.instant()), delivery.id(), delivery.lockToken());
    }

    public void failed(Delivery delivery, String code) {
        boolean exhausted = delivery.attempt() >= settings.maxAttempts();
        jdbc.update("""
                UPDATE email_outbox SET status = ?, next_attempt_at = ?, last_error_code = ?, lock_token = NULL, locked_until = NULL
                WHERE delivery_id = ? AND status = 'SENDING' AND lock_token = ?
                """, exhausted ? "FAILED" : "RETRY", Timestamp.from(clock.instant().plusSeconds(retryDelay(delivery.attempt()))),
                code, delivery.id(), delivery.lockToken());
    }

    public long retryDelay(int attempt) {
        return Math.min(3600, settings.initialRetryDelaySeconds() * (1L << Math.min(10, Math.max(0, attempt - 1))));
    }

    public Optional<Status> status(String id) {
        return jdbc.query("SELECT status, attempt_count, next_attempt_at, sent_at, last_error_code FROM email_outbox WHERE delivery_id = ?",
                (rs, row) -> new Status(rs.getString("status"), rs.getInt("attempt_count"),
                        rs.getTimestamp("next_attempt_at").toInstant(),
                        rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toInstant(),
                        rs.getString("last_error_code")), id).stream().findFirst();
    }

    public record Delivery(String id, String lockToken, int attempt, String from, String to, String subject, String body) {
        @Override public String toString() { return "Delivery[id=" + id + ", attempt=" + attempt + "]"; }
    }
    public record Status(String status, int attempts, Instant nextAttemptAt, Instant sentAt, String lastErrorCode) {}
}
