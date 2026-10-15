package kr.servicemonitor.event;

import java.time.Instant;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class LiveRequestStore {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final DefaultRedisScript<Long> record = new DefaultRedisScript<>();
    private final DefaultRedisScript<List> read = new DefaultRedisScript<>();

    public LiveRequestStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
        record.setLocation(new ClassPathResource("redis/record-request.lua"));
        record.setResultType(Long.class);
        read.setLocation(new ClassPathResource("redis/read-requests.lua"));
        read.setResultType(List.class);
    }

    public void record(RequestEvent event) {
        record(event, "live");
    }

    public void record(RequestEvent event, String window) {
        redis.execute(record, keys(event.serviceKey(), event.environment(), window),
                Long.toString(Instant.now().toEpochMilli()), Long.toString(event.occurredAt().toEpochMilli()),
                event.eventId().toString(), mapper.writeValueAsString(event));
    }

    public List<RequestEvent> recent(String serviceKey, String environment, Instant now) {
        return recent(serviceKey, environment, now, "live");
    }

    public List<RequestEvent> recent(String serviceKey, String environment, Instant now, String window) {
        List<?> values = redis.execute(read, keys(serviceKey, environment, window),
                Long.toString(now.minusSeconds(300).toEpochMilli()), Long.toString(now.toEpochMilli()));
        if (values == null) return List.of();
        return values.stream().map(value -> mapper.readValue(value.toString(), RequestEvent.class)).toList();
    }

    private List<String> keys(String serviceKey, String environment, String window) {
        if (!window.equals("live") && !window.equals("alerts")) throw new IllegalArgumentException("Unknown window");
        String prefix = "monitor:{" + serviceKey + ":" + environment + "}:" + (window.equals("live") ? "" : "alerts:");
        return List.of(prefix + "times", prefix + "events");
    }
}
