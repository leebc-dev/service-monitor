package kr.servicemonitor.email;

import jakarta.mail.MessagingException;
import java.nio.charset.StandardCharsets;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

@Component
public class EmailTransport {
    private final JavaMailSender sender;
    public EmailTransport(JavaMailSender sender) { this.sender = sender; }

    public void send(EmailOutbox.Delivery delivery) {
        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setValidateAddresses(true);
            helper.setFrom(delivery.from());
            helper.setTo(delivery.to());
            helper.setSubject(delivery.subject());
            helper.setText(delivery.body(), false);
            message.setHeader("X-Monitor-Delivery-ID", delivery.id());
            sender.send(message);
        } catch (MessagingException exception) {
            throw new MailPreparationException("Unable to prepare monitoring email", exception);
        }
    }
}
