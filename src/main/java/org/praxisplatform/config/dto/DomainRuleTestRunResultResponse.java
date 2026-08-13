package org.praxisplatform.config.dto;
import java.util.List;
import java.util.UUID;
public record DomainRuleTestRunResultResponse(
    UUID scenarioId, String scenarioKey, String expectedDecision, String candidateDecision,
    String activeDecision, String comparison, boolean candidateMatchesExpected,
    boolean activeMatchesExpected, List<String> candidateReasonCodes, List<String> activeReasonCodes,
    String candidatePlanDigest, String activePlanDigest, String factsDigest) {}
