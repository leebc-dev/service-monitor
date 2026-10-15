package kr.servicemonitor.alert;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AlertPolicy.class)
public class AlertConfiguration {
    @Bean
    Clock alertClock() { return Clock.systemUTC(); }
}
