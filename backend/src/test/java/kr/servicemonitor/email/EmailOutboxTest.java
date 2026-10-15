package kr.servicemonitor.email;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class EmailOutboxTest {
    @Test
    void retryBackoffIncreasesAndIsCapped() {
        var queue = new EmailOutbox(mock(JdbcTemplate.class), mock(TransactionTemplate.class), Clock.systemUTC(),
                EmailSettingsTest.settings(true, "127.0.0.1", false, false));
        assertEquals(30, queue.retryDelay(1));
        assertEquals(60, queue.retryDelay(2));
        assertEquals(240, queue.retryDelay(4));
        assertEquals(3600, queue.retryDelay(10));
    }

    @Test
    void disabledChannelDoesNotQueueHistoricalOrNewAlerts() {
        var jdbc = mock(JdbcTemplate.class);
        var queue = new EmailOutbox(jdbc, mock(TransactionTemplate.class), Clock.systemUTC(),
                EmailSettingsTest.settings(false, "smtp.gmail.com", true, true));
        queue.enqueueAlert("id", null, null, null, null);
        verifyNoInteractions(jdbc);
    }
}
