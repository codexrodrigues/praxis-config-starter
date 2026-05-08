package org.praxisplatform.config.ai.authoring;

import java.util.List;

public record AgenticAuthoringEvidenceBundle(
        String schemaVersion,
        String retrievalSource,
        List<Evidence> evidence,
        int evidenceCount
) {

    static final String SCHEMA_VERSION = "praxis-agentic-authoring-evidence-bundle.v1";

    public AgenticAuthoringEvidenceBundle {
        schemaVersion = safe(schemaVersion).isBlank() ? SCHEMA_VERSION : safe(schemaVersion);
        retrievalSource = safe(retrievalSource);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        evidenceCount = evidence.size();
    }

    static AgenticAuthoringEvidenceBundle of(String retrievalSource, List<Evidence> evidence) {
        return new AgenticAuthoringEvidenceBundle(
                SCHEMA_VERSION,
                safe(retrievalSource),
                evidence == null ? List.of() : evidence,
                evidence == null ? 0 : evidence.size());
    }

    static AgenticAuthoringEvidenceBundle empty() {
        return of("none", List.of());
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public record Evidence(
            String source,
            String kind,
            String ref,
            String summary,
            double confidence,
            List<String> matchedTerms,
            String tenantId,
            String environment,
            String releaseId
    ) {

        public Evidence {
            source = safe(source);
            kind = safe(kind);
            ref = safe(ref);
            summary = safe(summary);
            confidence = Math.max(0d, Math.min(1d, confidence));
            matchedTerms = matchedTerms == null ? List.of() : List.copyOf(matchedTerms);
            tenantId = safe(tenantId);
            environment = safe(environment);
            releaseId = safe(releaseId);
        }
    }
}
