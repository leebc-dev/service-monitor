package kr.servicemonitor.config;

import java.time.Duration;
import kr.servicemonitor.event.RequestEventConsumers.InvalidEventException;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class EventConsumerConfiguration {
    @Bean
    NewTopic deadLetters(@Value("${monitor.kafka.dead-letter-topic}") String topic) {
        return TopicBuilder.name(topic).partitions(3).replicas(1).build();
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumers, KafkaTemplate<String, String> kafka,
            @Value("${monitor.kafka.dead-letter-topic}") String deadLetterTopic) {
        var recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, exception) -> new TopicPartition(deadLetterTopic, record.partition()));
        recoverer.setFailIfSendResultIsError(true);
        recoverer.setWaitForSendResultTimeout(Duration.ofSeconds(5));
        // Infrastructure failures keep their offset and retry; malformed JSON is isolated in DLT.
        var errors = new DefaultErrorHandler(recoverer, new FixedBackOff(1000, FixedBackOff.UNLIMITED_ATTEMPTS));
        errors.addNotRetryableExceptions(InvalidEventException.class);
        var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
        factory.setConsumerFactory(consumers);
        factory.setCommonErrorHandler(errors);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        return factory;
    }
}
