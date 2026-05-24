package org.praxisplatform.config.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.praxisplatform.config.domain.AiAssistantObservation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiAssistantObservationRepository extends JpaRepository<AiAssistantObservation, UUID> {

    Optional<AiAssistantObservation> findFirstByThreadIdAndTurnIdOrderByCreatedAtDesc(UUID threadId, UUID turnId);

    Optional<AiAssistantObservation> findFirstByStreamIdOrderByCreatedAtDesc(UUID streamId);

    @Query("""
            select o from AiAssistantObservation o
            where o.tenantId = :tenantId
              and (:environment is null or o.environment = :environment)
              and (:from is null or o.createdAt >= :from)
              and (:to is null or o.createdAt <= :to)
              and (:surface is null or o.surface = :surface)
              and (:componentId is null or o.componentId = :componentId)
              and (:componentType is null or o.componentType = :componentType)
              and (:admissionOutcome is null or o.admissionOutcome = :admissionOutcome)
              and (:terminalOutcome is null or o.terminalOutcome = :terminalOutcome)
              and (:qualityOutcome is null or o.qualityOutcome = :qualityOutcome)
              and (:promptHash is null or o.promptHash = :promptHash)
              and (:hasFeedback is null
                    or (:hasFeedback = true and exists (
                        select f.feedbackId from AiAssistantObservationFeedback f
                        where f.observation = o
                    ))
                    or (:hasFeedback = false and not exists (
                        select f.feedbackId from AiAssistantObservationFeedback f
                        where f.observation = o
                    )))
            order by o.createdAt desc
            """)
    List<AiAssistantObservation> search(
            @Param("tenantId") String tenantId,
            @Param("environment") String environment,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("surface") String surface,
            @Param("componentId") String componentId,
            @Param("componentType") String componentType,
            @Param("admissionOutcome") String admissionOutcome,
            @Param("terminalOutcome") String terminalOutcome,
            @Param("qualityOutcome") String qualityOutcome,
            @Param("promptHash") String promptHash,
            @Param("hasFeedback") Boolean hasFeedback,
            Pageable pageable);

    @Query("""
            select o.admissionOutcome as admissionOutcome,
                   o.terminalOutcome as terminalOutcome,
                   o.qualityOutcome as qualityOutcome,
                   o.componentId as componentId,
                   o.componentType as componentType,
                   count(o) as total
            from AiAssistantObservation o
            where o.tenantId = :tenantId
              and (:environment is null or o.environment = :environment)
              and (:from is null or o.createdAt >= :from)
              and (:to is null or o.createdAt <= :to)
            group by o.admissionOutcome, o.terminalOutcome, o.qualityOutcome, o.componentId, o.componentType
            order by count(o) desc
            """)
    List<AiAssistantObservationSummaryRow> summarize(
            @Param("tenantId") String tenantId,
            @Param("environment") String environment,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);

    interface AiAssistantObservationSummaryRow {
        String getAdmissionOutcome();
        String getTerminalOutcome();
        String getQualityOutcome();
        String getComponentId();
        String getComponentType();
        long getTotal();
    }
}
