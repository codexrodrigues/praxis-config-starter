package org.praxisplatform.config.dto;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
public record DomainRuleTestRunResultResponse(
    UUID scenarioId, String scenarioKey, String expectedDecision, String candidateDecision,
    String activeDecision, String comparison, boolean candidateMatchesExpected,
    boolean activeMatchesExpected, JsonNode expectedOutput, JsonNode candidateOutput, JsonNode activeOutput,
    boolean candidateOutputMatchesExpected, boolean activeOutputMatchesExpected,
    List<String> expectedReasonCodes, List<String> candidateReasonCodes, List<String> activeReasonCodes,
    boolean candidateReasonCodesMatchExpected, boolean activeReasonCodesMatchExpected,
    List<String> expectedEffectIntents, List<String> candidateEffectIntents, List<String> activeEffectIntents,
    boolean candidateEffectsMatchExpected, boolean activeEffectsMatchExpected,
    String candidatePlanDigest, String activePlanDigest, String factsDigest) {}
