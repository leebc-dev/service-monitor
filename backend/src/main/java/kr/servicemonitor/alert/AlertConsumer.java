package kr.servicemonitor.alert;

import java.time.Clock;
import java.time.Instant;
import kr.servicemonitor.event.LiveRequestStore;
import kr.servicemonitor.event.RequestEvent;
import kr.servicemonitor.event.RequestEventConsumers.InvalidEventException;
import kr.servicemonitor.event.RequestMetrics;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class AlertConsumer {
    private final ObjectMapper mapper;
    private final LiveRequestStore windows;
    private final AlertPolicy policy;
    private final AlertDelivery delivery;
    private final Clock clock;
    public AlertConsumer(ObjectMapper mapper, LiveRequestStore windows, AlertPolicy policy,
                         AlertDelivery delivery, Clock clock) {
        this.mapper = mapper;
        this.windows = windows;
        this.policy = policy;
        this.delivery = delivery;
        this.clock = clock;
    }

    @KafkaListener(id = "request-alerts", groupId = "monitor-alerts-v1", topics = "${monitor.kafka.request-topic}")
    public void consume(String payload) {
        if (!policy.enabled()) return;
        RequestEvent event;
        try { event = mapper.readValue(payload, RequestEvent.class); }
        catch (Exception exception) { throw new InvalidEventException(exception); }
        Instant now = clock.instant();
        if (!event.occurredAt().isAfter(now.minusSeconds(300)) || event.occurredAt().isAfter(now)) return;
        // Own window avoids racing the separate live-metrics consumer. Replays remain idempotent.
        windows.record(event, "alerts");
        var summary = RequestMetrics.summarize(windows.recent(event.serviceKey(), event.environment(), now, "alerts"), now);
        for (var breach : policy.evaluate(summary)) delivery.deliver(event, breach, summary);
    }
}
