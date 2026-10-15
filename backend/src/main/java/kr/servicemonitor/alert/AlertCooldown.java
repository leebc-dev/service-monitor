package kr.servicemonitor.alert;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import kr.servicemonitor.alert.AlertPolicy.Type;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class AlertCooldown {
    private final StringRedisTemplate redis;
    private final Clock clock;
    public AlertCooldown(StringRedisTemplate redis, Clock clock) { this.redis = redis; this.clock = clock; }

    public boolean active(String service, String environment, Type type) {
        return Boolean.TRUE.equals(redis.hasKey(key(service, environment, type)));
    }

    public void remember(String service, String environment, Type type, Instant expiresAt) {
        Duration remaining = Duration.between(clock.instant(), expiresAt);
        if (remaining.toMillis() > 0)
            redis.opsForValue().setIfAbsent(key(service, environment, type), expiresAt.toString(), remaining);
    }

    private String key(String service, String environment, Type type) {
        return "monitor:{" + service + ":" + environment + "}:alert-cooldown:" + type;
    }
}
