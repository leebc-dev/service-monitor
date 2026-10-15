package kr.servicemonitor.event;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class RequestMetrics {
    private final LiveRequestStore live;
    public RequestMetrics(LiveRequestStore live) { this.live = live; }

    public Summary summarize(String serviceKey, String environment) {
        Instant now = Instant.now();
        return summarize(live.recent(serviceKey, environment, now), now);
    }

    public static Summary summarize(List<RequestEvent> events, Instant now) {
        long errors = events.stream().filter(event -> event.statusCode() >= 500).count();
        Double average = events.isEmpty() ? null : events.stream().mapToLong(RequestEvent::durationMs).average().orElse(0);
        List<RouteMetric> routes = events.stream().collect(Collectors.groupingBy(event -> event.method() + " " + event.route()))
                .entrySet().stream().map(entry -> new RouteMetric(entry.getKey(), entry.getValue().size(),
                        entry.getValue().stream().filter(event -> event.statusCode() >= 500).count(),
                        entry.getValue().stream().mapToLong(RequestEvent::durationMs).average().orElse(0)))
                .sorted(Comparator.comparingLong(RouteMetric::requestCount).reversed().thenComparing(RouteMetric::route))
                .limit(20).toList();
        return new Summary(300, now, events.size(), errors,
                events.isEmpty() ? null : 100.0 * errors / events.size(), average, routes);
    }

    public record Summary(int windowSeconds, Instant asOf, long requestCount, long serverErrorCount,
                          Double serverErrorRate, Double averageDurationMs, List<RouteMetric> routes) {}
    public record RouteMetric(String route, long requestCount, long serverErrorCount, double averageDurationMs) {}
}
