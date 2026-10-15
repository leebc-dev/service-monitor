package kr.servicemonitor.service;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonitoredServiceRepository extends JpaRepository<MonitoredService, Long> {
    List<MonitoredService> findAllByOrderByIdAsc();
}

