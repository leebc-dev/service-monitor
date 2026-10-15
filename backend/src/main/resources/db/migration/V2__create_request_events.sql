CREATE TABLE http_request_event (
    event_id CHAR(36) NOT NULL PRIMARY KEY,
    service_key VARCHAR(64) NOT NULL,
    environment VARCHAR(32) NOT NULL,
    method VARCHAR(10) NOT NULL,
    route VARCHAR(200) NOT NULL,
    status_code INT NOT NULL,
    duration_ms BIGINT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deployment_version VARCHAR(80) NOT NULL,
    INDEX idx_request_service_time (service_key, environment, occurred_at),
    INDEX idx_request_received (service_key, environment, received_at)
);
