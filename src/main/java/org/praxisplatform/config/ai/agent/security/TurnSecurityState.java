package org.praxisplatform.config.ai.agent.security;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-turn mutable security state tracking prompt-injection taint propagation,
 * verified entity IDs observed during reads or explicit user requests, and security findings.
 */
public final class TurnSecurityState {

    private static final Pattern NUMERIC_ID_PATTERN = Pattern.compile("\\b\\d{1,18}\\b");
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    private static final Pattern ALPHANUMERIC_CODE_PATTERN = Pattern.compile(
            "\\b[A-Za-z][A-Za-z0-9._-]{1,63}\\b");
    private static final Pattern COMPOSITE_KEY_PATTERN = Pattern.compile(
            "\\b[A-Za-z0-9._-]{1,32}[:|/][A-Za-z0-9._:-]{1,64}\\b");

    private final AgentSecurityMode mode;
    private boolean tainted;
    private final List<AgentSecurityFinding> findings = new ArrayList<>();
    private final Set<String> groundedEntityIds = new LinkedHashSet<>();

    public TurnSecurityState(AgentSecurityMode mode) {
        this.mode = mode == null ? AgentSecurityMode.ENFORCE : mode;
    }

    public AgentSecurityMode mode() {
        return mode;
    }

    public boolean isTainted() {
        return tainted;
    }

    public void markTainted(AgentSecurityFinding finding) {
        this.tainted = true;
        if (finding != null) {
            this.findings.add(finding);
        }
    }

    public void addFinding(AgentSecurityFinding finding) {
        if (finding != null) {
            this.findings.add(finding);
        }
    }

    public List<AgentSecurityFinding> findings() {
        return Collections.unmodifiableList(findings);
    }

    public void groundFromUserMessage(String userMessage) {
        extractGroundedTokens(userMessage);
    }

    public void groundFromTrustedHistoryText(String historyText) {
        extractGroundedTokens(historyText);
    }

    private void extractGroundedTokens(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher numMatcher = NUMERIC_ID_PATTERN.matcher(text);
        while (numMatcher.find()) {
            groundedEntityIds.add(numMatcher.group());
        }
        Matcher uuidMatcher = UUID_PATTERN.matcher(text);
        while (uuidMatcher.find()) {
            groundedEntityIds.add(uuidMatcher.group().toLowerCase());
        }
        Matcher codeMatcher = ALPHANUMERIC_CODE_PATTERN.matcher(text);
        while (codeMatcher.find()) {
            String token = codeMatcher.group();
            if (token.chars().anyMatch(Character::isDigit) || token.indexOf('-') >= 0 || token.indexOf('_') >= 0) {
                groundedEntityIds.add(token);
                groundedEntityIds.add(token.toLowerCase());
            }
        }
        Matcher compositeMatcher = COMPOSITE_KEY_PATTERN.matcher(text);
        while (compositeMatcher.find()) {
            String token = compositeMatcher.group();
            groundedEntityIds.add(token);
            groundedEntityIds.add(token.toLowerCase());
        }
    }

    public void recordGroundedEntityId(String entityId) {
        if (entityId != null && !entityId.isBlank()) {
            String normalized = entityId.trim();
            groundedEntityIds.add(normalized);
            groundedEntityIds.add(normalized.toLowerCase());
        }
    }

    public boolean isTargetGrounded(String entityId) {
        if (entityId == null || entityId.isBlank()) {
            return true;
        }
        String normalized = entityId.trim();
        return groundedEntityIds.contains(normalized) || groundedEntityIds.contains(normalized.toLowerCase());
    }

    public Set<String> groundedEntityIds() {
        return Collections.unmodifiableSet(groundedEntityIds);
    }
}
