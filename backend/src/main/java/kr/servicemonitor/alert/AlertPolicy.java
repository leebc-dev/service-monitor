package kr.servicemonitor.alert;

import jakarta.validation.constraints.*;
import java.util.ArrayList;
import java.util.List;
import kr.servicemonitor.event.RequestMetrics.Summary;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("monitor.alerts")
public record AlertPolicy(boolean enabled,
        @Min(1) @Max(100000) int minimumRequests,
        @DecimalMin("0.01") @DecimalMax("100") double errorRatePercent,
        @Min(1) @Max(3600000) long averageDurationMs,
        @Min(1) @Max(86400) long cooldownSeconds) {
    public enum Type { ERROR_RATE, LATENCY }
    public record Breach(Type type, double observedValue, double thresholdValue) {}

    public List<Breach> evaluate(Summary summary) {
        if (!enabled || summary.requestCount() < minimumRequests) return List.of();
        List<Breach> result = new ArrayList<>();
        if (summary.serverErrorRate() != null && summary.serverErrorRate() >= errorRatePercent)
            result.add(new Breach(Type.ERROR_RATE, summary.serverErrorRate(), errorRatePercent));
        if (summary.averageDurationMs() != null && summary.averageDurationMs() >= averageDurationMs)
            result.add(new Breach(Type.LATENCY, summary.averageDurationMs(), averageDurationMs));
        return List.copyOf(result);
    }
}
