package kr.servicemonitor.health;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("kafka")
public class KafkaHealthIndicator implements HealthIndicator {
    private final AdminClient adminClient;
    private final String topic;

    public KafkaHealthIndicator(AdminClient adminClient,
            @Value("${monitor.kafka.request-topic}") String topic) {
        this.adminClient = adminClient;
        this.topic = topic;
    }

    @Override
    public Health health() {
        try {
            adminClient.describeTopics(List.of(topic)).allTopicNames().get(2, TimeUnit.SECONDS);
            return Health.up().build();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return Health.down().build();
        } catch (Exception exception) {
            return Health.down().build();
        }
    }
}

