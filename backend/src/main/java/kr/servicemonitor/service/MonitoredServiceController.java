package kr.servicemonitor.service;

import java.util.List;
import java.time.Instant;
import kr.servicemonitor.event.RequestHistory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/services")
public class MonitoredServiceController {
    private final MonitoredServiceRepository repository;
    private final RequestHistory history;

    public MonitoredServiceController(MonitoredServiceRepository repository, RequestHistory history) {
        this.repository = repository;
        this.history = history;
    }

    @GetMapping
    public List<ServiceResponse> list() {
        return repository.findAllByOrderByIdAsc().stream()
                .map(service -> {
                    Instant last = history.lastReceived(service.getServiceKey(), service.getEnvironment());
                    String status = last == null ? "NOT_CONNECTED" : last.isAfter(Instant.now().minusSeconds(60)) ? "RECEIVING" : "IDLE";
                    return new ServiceResponse(service.getServiceKey(), service.getDisplayName(), service.getEnvironment(), status, last);
                })
                .toList();
    }

    public record ServiceResponse(String serviceKey, String displayName, String environment,
                                  String collectionStatus, Instant lastReceivedAt) {
    }
}

