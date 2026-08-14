package org.praxisplatform.config.dto;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
public record DomainRuleTestRunResultRequest(
    UUID scenarioId, String scenarioKey, String candidateDecision, String activeDecision,
    JsonNode candidateOutput, JsonNode activeOutput,
    List<String> candidateReasonCodes, List<String> activeReasonCodes,
    List<String> candidateEffectIntents, List<String> activeEffectIntents,
    String candidatePlanDigest, String activePlanDigest, String factsDigest) {}
