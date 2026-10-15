package kr.servicemonitor.email;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("monitor.email")
public record EmailSettings(boolean enabled,
        @Email @Size(max = 254) @Pattern(regexp = "[^\\r\\n]*") String from,
        @Email @Size(max = 254) @Pattern(regexp = "[^\\r\\n]*") String to,
        String dashboardUrl,
        @Min(1) @Max(10) int maxAttempts,
        @Min(1) @Max(3600) long initialRetryDelaySeconds,
        @Min(100) @Max(60000) long pollIntervalMs,
        @Valid @NotNull Smtp smtp) {

    public EmailSettings {
        from = from == null ? "" : from.trim();
        to = to == null ? "" : to.trim();
    }

    public record Smtp(String host, @Min(1) @Max(65535) int port, String username, String password,
                       boolean auth, boolean starttlsEnabled, boolean sslEnabled) {
        public Smtp {
            host = host == null ? "" : host.trim();
            username = username == null ? "" : username.trim();
            password = password == null ? "" : password;
        }
        @Override public String toString() { return "Smtp[credentials redacted]"; }
    }

    public boolean configured() {
        return smtp != null && !from.isBlank() && !to.isBlank() && !smtp.host().isBlank()
                && (!smtp.auth() || (!smtp.username().isBlank() && !smtp.password().isBlank()));
    }

    @AssertTrue(message = "Email requires sender, recipient, SMTP settings, and TLS for remote servers")
    public boolean isConfigurationValid() {
        if (!enabled) return true;
        if (smtp == null) return false;
        boolean loopback = smtp.host().equals("127.0.0.1") || smtp.host().equals("localhost") || smtp.host().equals("::1");
        return configured() && !(smtp.sslEnabled() && smtp.starttlsEnabled())
                && (loopback || smtp.sslEnabled() || smtp.starttlsEnabled());
    }

    @AssertTrue(message = "Dashboard URL must be an HTTP(S) URL without credentials or line breaks")
    public boolean isDashboardUrlValid() {
        try {
            URI uri = URI.create(dashboardUrl);
            return (uri.getScheme().equals("http") || uri.getScheme().equals("https"))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (RuntimeException exception) { return false; }
    }

    public Channel channel() {
        int at = to.indexOf('@');
        String masked = at > 0 ? to.substring(0, 1) + "***" + to.substring(at) : null;
        return new Channel(enabled, configured(), masked, maxAttempts);
    }

    public record Channel(boolean enabled, boolean configured, String recipient, int maxAttempts) {}
    @Override public String toString() { return "EmailSettings[enabled=" + enabled + ", credentials redacted]"; }
}
