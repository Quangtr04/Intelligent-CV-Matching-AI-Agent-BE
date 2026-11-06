package com.example.intelligent_cv_matching_ai_agent.service.shared.consultation;

import com.example.intelligent_cv_matching_ai_agent.repository.shared.consultation.ConsultationBookingRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ConsultationBookingMaintenance {

    private static final Logger LOG = LoggerFactory.getLogger(ConsultationBookingMaintenance.class);

    private final ConsultationBookingRepository db;

    private final ConsultationBookingService service;

    public ConsultationBookingMaintenance(
        ConsultationBookingRepository db,
        ConsultationBookingService service) {
        this.db = db;
        this.service = service;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void settle() {
        for (var row : db.due()) {
            try {
                service.settleDue((UUID) row.get("id"));
            } catch (RuntimeException ex) {
                LOG.warn("Could not settle consultation booking {}: {}", row.get("id"), ex.getMessage());
            }
        }
    }
}
