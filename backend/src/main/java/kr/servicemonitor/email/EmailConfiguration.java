package kr.servicemonitor.email;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(EmailSettings.class)
public class EmailConfiguration {
    @Bean
    JavaMailSender monitorMailSender(EmailSettings settings) {
        var smtp = settings.smtp();
        var sender = new JavaMailSenderImpl();
        sender.setHost(smtp.host());
        sender.setPort(smtp.port());
        sender.setUsername(smtp.username());
        sender.setPassword(smtp.password());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        var properties = sender.getJavaMailProperties();
        properties.setProperty("mail.smtp.auth", Boolean.toString(smtp.auth()));
        properties.setProperty("mail.smtp.starttls.enable", Boolean.toString(smtp.starttlsEnabled()));
        properties.setProperty("mail.smtp.starttls.required", Boolean.toString(smtp.starttlsEnabled()));
        properties.setProperty("mail.smtp.ssl.enable", Boolean.toString(smtp.sslEnabled()));
        properties.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        properties.setProperty("mail.smtp.connectiontimeout", "5000");
        properties.setProperty("mail.smtp.timeout", "5000");
        properties.setProperty("mail.smtp.writetimeout", "5000");
        properties.setProperty("mail.debug", "false");
        return sender;
    }
}
