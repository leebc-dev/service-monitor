package kr.servicemonitor.event;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public record RequestEvent(
        @NotNull UUID eventId,
        @NotBlank @Pattern(regexp = "[a-z0-9-]{1,64}") String serviceKey,
        @NotBlank @Pattern(regexp = "[a-z0-9-]{1,32}") String environment,
        @NotBlank @Pattern(regexp = "GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE|CONNECT") String method,
        @NotBlank @Size(max = 200) @Pattern(regexp = "UNMATCHED|/[a-zA-Z0-9_/{}/.*:-]*") String route,
        @Min(100) @Max(599) int statusCode,
        @Min(0) @Max(3600000) long durationMs,
        @NotNull Instant occurredAt,
        @NotBlank @Size(max = 80) String deploymentVersion) {
}
