package kr.servicemonitor.email;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class EmailSettingsTest {
    static EmailSettings settings(boolean enabled, String host, boolean tls, boolean auth) {
        return new EmailSettings(enabled, "sender@example.test", "receiver@example.test", "http://127.0.0.1:15273/#alerts", 5, 30, 5000,
                new EmailSettings.Smtp(host, 587, "sender@example.test", "test-app-password", auth, tls, false));
    }

    @Test
    void gmailRequiresSecureTransport() {
        assertTrue(settings(true, "smtp.gmail.com", true, true).isConfigurationValid());
        assertFalse(settings(true, "smtp.gmail.com", false, true).isConfigurationValid());
    }

    @Test
    void localTestServerCanUsePlainSmtpButMissingCredentialsCannotEnableRemoteMail() {
        assertTrue(settings(true, "127.0.0.1", false, false).isConfigurationValid());
        var incomplete = new EmailSettings(true, "", "", "http://127.0.0.1:15273", 5, 30, 5000,
                new EmailSettings.Smtp("smtp.gmail.com", 587, "", "", true, true, false));
        assertFalse(incomplete.isConfigurationValid());
        assertFalse(incomplete.configured());
    }

    @Test
    void diagnosticRepresentationsDoNotIncludeCredentialsAndRecipientIsMasked() {
        var settings = settings(true, "smtp.gmail.com", true, true);
        assertFalse(settings.toString().contains("test-app-password"));
        assertFalse(settings.smtp().toString().contains("test-app-password"));
        assertEquals("r***@example.test", settings.channel().recipient());
    }
}
