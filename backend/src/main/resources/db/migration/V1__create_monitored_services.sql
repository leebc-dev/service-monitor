CREATE TABLE monitored_service (
    id BIGINT NOT NULL AUTO_INCREMENT,
    service_key VARCHAR(64) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    environment VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_monitored_service_key UNIQUE (service_key)
);

INSERT INTO monitored_service (service_key, display_name, environment)
VALUES ('football-lounge', '풋볼라운지', 'development');

