package org.praxisplatform.config.ai.authoring;

import java.util.ArrayList;
import java.util.List;

public class AgenticAuthoringResourceDiscoveryService {

    private static final String TOOL_NAME = "searchApiResources";

    private final AgenticAuthoringApiMetadataCandidateCatalog candidateCatalog;

    public AgenticAuthoringResourceDiscoveryService(
            AgenticAuthoringApiMetadataCandidateCatalog candidateCatalog) {
        this.candidateCatalog = candidateCatalog;
    }

    public AgenticAuthoringResourceCandidatesResult search(
            AgenticAuthoringResourceCandidatesRequest request) {
        String retrievalQuery = retrievalQuery(request);
        String artifactKind = artifactKind(request);
        List<String> warnings = new ArrayList<>();
        if (retrievalQuery.isBlank()) {
            warnings.add("resource-discovery-query-required");
            return new AgenticAuthoringResourceCandidatesResult(
                    false,
                    TOOL_NAME,
                    retrievalQuery,
                    artifactKind,
                    List.of(),
                    List.copyOf(warnings));
        }
        List<AgenticAuthoringCandidate> candidates = candidateCatalog == null
                ? List.of()
                : candidateCatalog.discover(retrievalQuery, artifactKind);
        int limit = limit(request);
        if (candidates.size() > limit) {
            candidates = candidates.subList(0, limit);
            warnings.add("resource-candidates-limited");
        }
        if (candidates.isEmpty()) {
            warnings.add("resource-candidates-empty");
        }
        return new AgenticAuthoringResourceCandidatesResult(
                true,
                TOOL_NAME,
                retrievalQuery,
                artifactKind,
                List.copyOf(candidates),
                List.copyOf(warnings));
    }

    private String retrievalQuery(AgenticAuthoringResourceCandidatesRequest request) {
        if (request == null) {
            return "";
        }
        if (request.retrievalQuery() != null && !request.retrievalQuery().isBlank()) {
            return request.retrievalQuery().trim();
        }
        return request.userPrompt() == null ? "" : request.userPrompt().trim();
    }

    private String artifactKind(AgenticAuthoringResourceCandidatesRequest request) {
        if (request == null || request.artifactKind() == null || request.artifactKind().isBlank()) {
            return "unknown";
        }
        return request.artifactKind().trim();
    }

    private int limit(AgenticAuthoringResourceCandidatesRequest request) {
        if (request == null || request.limit() == null) {
            return 8;
        }
        return Math.max(1, Math.min(20, request.limit()));
    }
}
