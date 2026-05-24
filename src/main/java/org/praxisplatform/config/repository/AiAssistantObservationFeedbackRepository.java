package org.praxisplatform.config.repository;

import java.util.List;
import java.util.UUID;
import org.praxisplatform.config.domain.AiAssistantObservationFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiAssistantObservationFeedbackRepository
        extends JpaRepository<AiAssistantObservationFeedback, UUID> {

    List<AiAssistantObservationFeedback> findByObservation_ObservationIdOrderByCreatedAtDesc(UUID observationId);
}
