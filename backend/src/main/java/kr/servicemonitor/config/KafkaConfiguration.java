package kr.servicemonitor.config;

import java.util.Map;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfiguration {
    @Bean(destroyMethod = "close")
    AdminClient monitoringKafkaAdmin(@Value("${spring.kafka.bootstrap-servers}") String servers) {
        return AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, servers,
                AdminClientConfig.CLIENT_ID_CONFIG, "service-monitor-health",
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 1500,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 2000));
    }

    @Bean
    NewTopic requestEvents(@Value("${monitor.kafka.request-topic}") String topic) {
        return TopicBuilder.name(topic)
                .partitions(3)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, "259200000")
                .build();
    }
}

