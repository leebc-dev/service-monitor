package kr.servicemonitor.alert;

import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class AlertCooldownTest {
    private final Instant now = Instant.parse("2026-10-14T12:00:00Z");

    @Test
    @SuppressWarnings("unchecked")
    void usesAtomicSetIfAbsentWithRemainingTtlAndIndependentRuleKey() {
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var gate = new AlertCooldown(redis, Clock.fixed(now, ZoneOffset.UTC));
        gate.remember("football-lounge", "development", AlertPolicy.Type.ERROR_RATE, now.plusSeconds(480));
        gate.remember("football-lounge", "development", AlertPolicy.Type.LATENCY, now.plusSeconds(480));
        verify(values).setIfAbsent("monitor:{football-lounge:development}:alert-cooldown:ERROR_RATE", now.plusSeconds(480).toString(), Duration.ofSeconds(480));
        verify(values).setIfAbsent("monitor:{football-lounge:development}:alert-cooldown:LATENCY", now.plusSeconds(480).toString(), Duration.ofSeconds(480));
    }

    @Test
    void doesNotRecreateExpiredCooldown() {
        var redis = mock(StringRedisTemplate.class);
        var gate = new AlertCooldown(redis, Clock.fixed(now, ZoneOffset.UTC));
        gate.remember("football-lounge", "development", AlertPolicy.Type.ERROR_RATE, now.minusSeconds(1));
        verifyNoInteractions(redis);
    }
}
