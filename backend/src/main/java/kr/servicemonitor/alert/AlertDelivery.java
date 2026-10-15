package kr.servicemonitor.alert;

import java.time.Instant;
import kr.servicemonitor.alert.AlertPolicy.Breach;
import kr.servicemonitor.event.RequestEvent;
import kr.servicemonitor.event.RequestMetrics.Summary;
import org.springframework.stereotype.Service;

@Service
public class AlertDelivery {
    private final AlertCooldown cooldown;
    private final AlertRepository repository;
    private final AlertPolicy policy;
    public AlertDelivery(AlertCooldown cooldown, AlertRepository repository, AlertPolicy policy) {
        this.cooldown = cooldown;
        this.repository = repository;
        this.policy = policy;
    }

    public void deliver(RequestEvent event, Breach breach, Summary summary) {
        if (cooldown.active(event.serviceKey(), event.environment(), breach.type())) return;
        Instant notifiedAt = repository.saveIfDue(event, breach, summary, policy.cooldownSeconds());
        // Only suppress after the notification is durable. Failures retry through Kafka.
        cooldown.remember(event.serviceKey(), event.environment(), breach.type(), notifiedAt.plusSeconds(policy.cooldownSeconds()));
    }
}
