package kr.servicemonitor.event;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/ingest/http-requests")
public class EventIngestController {
    private final byte[] apiKey;
    private final String topic;
    private final KafkaTemplate<String, String> kafka;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public EventIngestController(@Value("${monitor.ingest.api-key}") String apiKey,
            @Value("${monitor.kafka.request-topic}") String topic,
            KafkaTemplate<String, String> kafka, JdbcTemplate jdbc, ObjectMapper mapper) {
        if (apiKey.length() < 32) throw new IllegalArgumentException("Ingest API key must be at least 32 characters");
        this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
        this.topic = topic;
        this.kafka = kafka;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> ingest(@RequestHeader(value = "X-Monitor-Key", defaultValue = "") String key,
                                     @Valid @RequestBody EventBatch batch) {
        if (!MessageDigest.isEqual(apiKey, key.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        Instant now = Instant.now();
        // Validate the whole batch before publishing any event.
        for (RequestEvent event : batch.events()) {
            if (event.occurredAt().isBefore(now.minus(72, ChronoUnit.HOURS))
                    || event.occurredAt().isAfter(now.plusSeconds(60))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid event time");
            }
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM monitored_service WHERE service_key = ? AND environment = ?",
                    Integer.class, event.serviceKey(), event.environment());
            if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown service");
        }
        try {
            var sends = batch.events().stream().map(event -> kafka.send(topic,
                    event.serviceKey() + ":" + event.environment(), mapper.writeValueAsString(event)))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(sends).get(4, TimeUnit.SECONDS);
            return Map.of("accepted", batch.events().size());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        } catch (Exception exception) {
            // A timeout can follow a successful publish. Retries must retain eventId.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    public record EventBatch(@NotEmpty @Size(max = 100) List<@NotNull @Valid RequestEvent> events) {}
}
