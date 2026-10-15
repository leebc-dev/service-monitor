CREATE TABLE alert_rule_state (
    service_key VARCHAR(64) NOT NULL,
    environment VARCHAR(32) NOT NULL,
    rule_type VARCHAR(32) NOT NULL,
    last_notified_at DATETIME(6) NULL,
    PRIMARY KEY (service_key, environment, rule_type)
);

CREATE TABLE alert_notification (
    alert_id CHAR(36) NOT NULL PRIMARY KEY,
    service_key VARCHAR(64) NOT NULL,
    environment VARCHAR(32) NOT NULL,
    rule_type VARCHAR(32) NOT NULL,
    observed_value DOUBLE NOT NULL,
    threshold_value DOUBLE NOT NULL,
    request_count BIGINT NOT NULL,
    window_seconds INT NOT NULL,
    window_ended_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_alert_service_time (service_key, environment, created_at)
);
