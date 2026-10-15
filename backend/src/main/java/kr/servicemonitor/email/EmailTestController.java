package kr.servicemonitor.email;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/notifications/email")
public class EmailTestController {
    private final EmailOutbox outbox;
    private final EmailSettings settings;
    private final byte[] apiKey;
    public EmailTestController(EmailOutbox outbox, EmailSettings settings, @Value("${monitor.ingest.api-key}") String apiKey) {
        this.outbox = outbox; this.settings = settings; this.apiKey = apiKey.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, String> test(@RequestHeader(value = "X-Monitor-Key", defaultValue = "") String key,
                                    @Valid @RequestBody TestRequest request) {
        authorize(key);
        if (!settings.enabled()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Email disabled");
        return Map.of("deliveryId", outbox.enqueueTest(request.requestId()));
    }

    @GetMapping("/deliveries/{id}")
    public EmailOutbox.Status status(@RequestHeader(value = "X-Monitor-Key", defaultValue = "") String key,
                                    @PathVariable UUID id) {
        authorize(key);
        return outbox.status(id.toString()).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void authorize(String key) {
        if (!MessageDigest.isEqual(apiKey, key.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    public record TestRequest(@NotNull UUID requestId) {}
}
