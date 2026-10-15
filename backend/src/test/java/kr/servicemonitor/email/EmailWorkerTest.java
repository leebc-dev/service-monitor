package kr.servicemonitor.email;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

class EmailWorkerTest {
    private final EmailOutbox queue = mock(EmailOutbox.class);
    private final EmailTransport transport = mock(EmailTransport.class);
    private final EmailWorker worker = new EmailWorker(queue, transport);
    private final EmailOutbox.Delivery delivery = new EmailOutbox.Delivery("id", "token", 1, "from@example.test", "to@example.test", "subject", "body");

    @Test
    void sendsOnlyAfterClaimAndMarksSentAfterSmtpAcceptance() {
        when(queue.claim()).thenReturn(Optional.of(delivery));
        assertTrue(worker.dispatchOne());
        var order = inOrder(queue, transport);
        order.verify(queue).claim();
        order.verify(transport).send(delivery);
        order.verify(queue).sent(delivery);
        verify(queue, never()).failed(any(), any());
    }

    @Test
    void retryUsesSafeErrorCodeWithoutSavingRawSmtpMessage() {
        when(queue.claim()).thenReturn(Optional.of(delivery));
        doThrow(new MailAuthenticationException("secret@example.test sensitive SMTP detail")).when(transport).send(delivery);
        assertTrue(worker.dispatchOne());
        verify(queue).failed(delivery, "SMTP_AUTH");
        verify(queue, never()).sent(any());
    }

    @Test
    void smtpFailureDoesNotMarkMessageSent() {
        when(queue.claim()).thenReturn(Optional.of(delivery));
        doThrow(new MailSendException("temporary rejection")).when(transport).send(delivery);
        worker.dispatchOne();
        verify(queue).failed(delivery, "SMTP_SEND");
        verify(queue, never()).sent(any());
    }

    @Test
    void commitFailureAfterSmtpAcceptanceLeavesLeaseForRecovery() {
        when(queue.claim()).thenReturn(Optional.of(delivery));
        doThrow(new IllegalStateException("database unavailable")).when(queue).sent(delivery);
        assertThrows(IllegalStateException.class, () -> worker.dispatchOne());
        verify(queue, never()).failed(any(), any());
        verify(transport, times(1)).send(delivery);
    }

    @Test
    void emptyQueueDoesNotConnectToSmtp() {
        when(queue.claim()).thenReturn(Optional.empty());
        assertFalse(worker.dispatchOne());
        verifyNoInteractions(transport);
    }
}
