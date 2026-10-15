package kr.servicemonitor.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "monitor.email.enabled", havingValue = "true")
public class EmailWorker {
    private static final Logger log = LoggerFactory.getLogger(EmailWorker.class);
    private final EmailOutbox outbox;
    private final EmailTransport transport;
    public EmailWorker(EmailOutbox outbox, EmailTransport transport) { this.outbox = outbox; this.transport = transport; }

    @Scheduled(fixedDelayString = "${monitor.email.poll-interval-ms}", initialDelayString = "${monitor.email.poll-interval-ms}")
    public void poll() {
        try {
            // Claim one job at a time so leases do not expire while waiting in a local batch.
            for (int i = 0; i < 5; i++) if (!dispatchOne()) break;
        } catch (RuntimeException exception) {
            // Never log SMTP responses, credentials or recipient addresses.
            log.warn("Email queue processing interrupted; pending work will resume. type={}", exception.getClass().getSimpleName());
        }
    }

    boolean dispatchOne() {
        var claimed = outbox.claim();
        if (claimed.isEmpty()) return false;
        var delivery = claimed.get();
        try { transport.send(delivery); }
        catch (RuntimeException exception) {
            String code = errorCode(exception);
            outbox.failed(delivery, code);
            log.warn("Monitoring email attempt failed. id={}, attempt={}, code={}", delivery.id(), delivery.attempt(), code);
            return true;
        }
        // A DB failure here leaves a lease for recovery; SMTP acceptance cannot be rolled back.
        outbox.sent(delivery);
        log.info("Monitoring email accepted by SMTP. id={}, attempt={}", delivery.id(), delivery.attempt());
        return true;
    }

    static String errorCode(RuntimeException exception) {
        if (exception instanceof MailAuthenticationException) return "SMTP_AUTH";
        if (exception instanceof MailPreparationException) return "MESSAGE_FORMAT";
        if (exception instanceof MailSendException) return "SMTP_SEND";
        return "DELIVERY_ERROR";
    }
}
