package kr.servicemonitor.event;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/services/{serviceKey}")
public class MetricsController {
    private final RequestMetrics metrics;
    private final RequestHistory history;
    private final RequestAnalytics analytics;
    public MetricsController(RequestMetrics metrics, RequestHistory history, RequestAnalytics analytics) {
        this.metrics = metrics;
        this.history = history;
        this.analytics = analytics;
    }

    @GetMapping("/analytics")
    public RequestAnalytics.Response analytics(@PathVariable String serviceKey,
            @RequestParam(defaultValue = "development") String environment,
            @RequestParam(defaultValue = "15") int minutes) {
        validateKey(serviceKey, environment);
        if (minutes != 5 && minutes != 15 && minutes != 60)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minutes must be 5, 15, or 60");
        return analytics.query(serviceKey, environment, minutes);
    }

    @GetMapping("/metrics")
    public RequestMetrics.Summary metrics(@PathVariable String serviceKey,
            @RequestParam(defaultValue = "development") String environment) {
        validateKey(serviceKey, environment);
        return metrics.summarize(serviceKey, environment);
    }

    @GetMapping("/events")
    public List<RequestHistory.RecentEvent> recent(@PathVariable String serviceKey,
            @RequestParam(defaultValue = "development") String environment) {
        validateKey(serviceKey, environment);
        return history.recent(serviceKey, environment);
    }

    private void validateKey(String serviceKey, String environment) {
        if (!serviceKey.matches("[a-z0-9-]{1,64}") || !environment.matches("[a-z0-9-]{1,32}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }
}
