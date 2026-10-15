package kr.servicemonitor.event;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class RequestEventConsumers {
    private final ObjectMapper mapper;
    private final RequestHistory history;
    private final LiveRequestStore live;
    public RequestEventConsumers(ObjectMapper mapper, RequestHistory history, LiveRequestStore live) {
        this.mapper = mapper;
        this.history = history;
        this.live = live;
    }

    @KafkaListener(id = "request-history", groupId = "monitor-history-v1", topics = "${monitor.kafka.request-topic}")
    public void history(String payload) { history.save(decode(payload)); }

    @KafkaListener(id = "request-live", groupId = "monitor-live-v1", topics = "${monitor.kafka.request-topic}")
    public void live(String payload) { live.record(decode(payload)); }

    private RequestEvent decode(String payload) {
        try { return mapper.readValue(payload, RequestEvent.class); }
        catch (Exception exception) { throw new InvalidEventException(exception); }
    }

    public static class InvalidEventException extends RuntimeException {
        public InvalidEventException(Throwable cause) { super("Invalid request event", cause); }
    }
}
