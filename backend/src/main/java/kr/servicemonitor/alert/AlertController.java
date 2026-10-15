package kr.servicemonitor.alert;

import java.util.List;
import kr.servicemonitor.email.EmailSettings;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/services/{serviceKey}/alerts")
public class AlertController {
    private final AlertRepository repository;
    private final AlertPolicy policy;
    private final EmailSettings email;
    public AlertController(AlertRepository repository, AlertPolicy policy, EmailSettings email) {
        this.repository = repository; this.policy = policy; this.email = email;
    }

    @GetMapping
    public Response recent(@PathVariable String serviceKey, @RequestParam(defaultValue = "development") String environment) {
        if (!serviceKey.matches("[a-z0-9-]{1,64}") || !environment.matches("[a-z0-9-]{1,32}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return new Response(policy, repository.recent(serviceKey, environment), email.channel());
    }

    public record Response(AlertPolicy policy, List<AlertRepository.Notification> notifications, EmailSettings.Channel email) {}
}
