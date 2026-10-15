package kr.servicemonitor.service;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "monitored_service")
public class MonitoredService {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String serviceKey;

    @Column(nullable = false, length = 100)
    private String displayName;

    @Column(nullable = false, length = 32)
    private String environment;

    protected MonitoredService() {
    }

    public String getServiceKey() { return serviceKey; }
    public String getDisplayName() { return displayName; }
    public String getEnvironment() { return environment; }
}

