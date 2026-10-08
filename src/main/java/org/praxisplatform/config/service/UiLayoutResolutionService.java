package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.praxisplatform.config.dto.AppliedUiLayoutLayer;
import org.praxisplatform.config.dto.EffectiveUiLayoutResponse;
import org.praxisplatform.config.dto.UiLayoutTarget;

public final class UiLayoutResolutionService implements EffectiveUiLayoutResolver {
    static final String SCHEMA_VERSION = "praxis.effective-ui-layout/v1";

    private final ObjectMapper objectMapper;
    private final CanonicalJsonHashService hashService;
    private final EffectiveUiAudienceProvider audienceProvider;
    private final UiLayoutCandidateSource candidateSource;
    private final Clock clock;

    public UiLayoutResolutionService(
            ObjectMapper objectMapper,
            CanonicalJsonHashService hashService,
            EffectiveUiAudienceProvider audienceProvider,
            UiLayoutCandidateSource candidateSource,
            Clock clock) {
        this.objectMapper = objectMapper;
        this.hashService = hashService;
        this.audienceProvider = audienceProvider;
        this.candidateSource = candidateSource;
        this.clock = clock;
    }

    @Override
    public Optional<EffectiveUiLayoutResponse> resolve(
            Principal principal, String expectedContextVersion, UiLayoutTarget target) {
        if (principal == null) throw new IllegalArgumentException("principal is required.");
        if (expectedContextVersion == null || expectedContextVersion.isBlank()) {
            throw new IllegalArgumentException("expectedContextVersion is required.");
        }

        EffectiveUiAudience audience;
        try {
            audience = audienceProvider.resolve(principal, expectedContextVersion.trim());
        } catch (UiLayoutResolutionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new UiLayoutResolutionException(
                    UiLayoutResolutionException.Code.AUDIENCE_SOURCE_UNAVAILABLE,
                    "Authoritative UI audience is unavailable.",
                    null);
        }
        if (audience == null) {
            throw new UiLayoutResolutionException(
                    UiLayoutResolutionException.Code.AUDIENCE_SOURCE_UNAVAILABLE,
                    "Authoritative UI audience is unavailable.",
                    null);
        }
        if (!expectedContextVersion.trim().equals(audience.contextVersion())) {
            throw new UiLayoutResolutionException(
                    UiLayoutResolutionException.Code.CONTEXT_STALE,
                    "Runtime context changed before layout resolution.",
                    null);
        }

        return resolveForAudience(audience, target, candidateSource);
    }

    /** Package-private engine seam used by aggregate reads; matching and merge stay canonical here. */
    Optional<EffectiveUiLayoutResponse> resolveForAudience(
            EffectiveUiAudience audience, UiLayoutTarget target, UiLayoutCandidateSource source) {
        return resolveDetailedForAudience(audience, target, source).map(UiLayoutResolutionResult::response);
    }

    /** Internal result retains exactly the candidates admitted by the canonical matcher. */
    Optional<UiLayoutResolutionResult> resolveDetailedForAudience(
            EffectiveUiAudience audience, UiLayoutTarget target, UiLayoutCandidateSource source) {
        List<UiLayoutResolutionCandidate> active;
        try {
            active = source.findActive(audience.tenant(), audience.environment(), target);
        } catch (UiLayoutResolutionException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new UiLayoutResolutionException(
                    UiLayoutResolutionException.Code.LAYOUT_SOURCE_UNAVAILABLE,
                    "Authoritative UI layout source is unavailable.",
                    null);
        }
        if (active == null) {
            throw new UiLayoutResolutionException(
                    UiLayoutResolutionException.Code.LAYOUT_SOURCE_UNAVAILABLE,
                    "Authoritative UI layout source is unavailable.",
                    null);
        }
        List<UiLayoutResolutionCandidate> matching = active
                .stream()
                .filter(candidate -> candidate.target().equals(target))
                .filter(candidate -> candidate.selector().matches(audience))
                .sorted(Comparator.comparing(UiLayoutResolutionCandidate::layerClass)
                        .thenComparingInt(UiLayoutResolutionCandidate::priority)
                        .thenComparing(UiLayoutResolutionCandidate::revisionRef))
                .toList();
        if (matching.isEmpty()) return Optional.empty();

        Set<String> authorablePaths = source.authorablePaths(target);
        Set<String> removablePaths = source.removablePaths(target);
        ObjectNode document = objectMapper.createObjectNode();
        Map<WriteSlot, Map<String, WriteValue>> writes = new HashMap<>();
        List<AppliedUiLayoutLayer> layers = new ArrayList<>();
        for (UiLayoutResolutionCandidate candidate : matching) {
            JsonNode patch = candidate.patch();
            if (!hashService.sha256Exact(patch).equals(candidate.contentHash())) {
                throw new UiLayoutResolutionException(
                        UiLayoutResolutionException.Code.LAYOUT_REVISION_INTEGRITY,
                        "Layout revision content does not match its attested hash.",
                        null);
            }
            try {
                source.validatePatch(target, patch);
            } catch (UiLayoutResolutionException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new UiLayoutResolutionException(
                        UiLayoutResolutionException.Code.PROTECTED_PRESENTATION_INVARIANT,
                        "Layout revision violates component presentation policy.",
                        null);
            }
            validateAndRecord(patch, "", candidate, authorablePaths, removablePaths, writes);
            merge(document, (ObjectNode) patch);
            layers.add(new AppliedUiLayoutLayer(
                    candidate.layerClass().name(),
                    candidate.revisionRef(),
                    candidate.contentHash(),
                    candidate.priority(),
                    candidate.safeLabel()));
        }

        String compositeEtag = hashService.sha256(Map.of(
                "schemaVersion", SCHEMA_VERSION,
                "target", target,
                "contextVersion", audience.contextVersion(),
                "document", document,
                "layers", layers));
        return Optional.of(new UiLayoutResolutionResult(new EffectiveUiLayoutResponse(
                SCHEMA_VERSION,
                target,
                audience.contextVersion(),
                document.deepCopy(),
                compositeEtag,
                List.copyOf(layers),
                List.of(),
                Instant.now(clock)), List.copyOf(matching)));
    }

    private void validateAndRecord(
            JsonNode node,
            String parent,
            UiLayoutResolutionCandidate candidate,
            Set<String> authorablePaths,
            Set<String> removablePaths,
            Map<WriteSlot, Map<String, WriteValue>> writes) {
        node.properties().forEach(entry -> {
            String path = parent + "/" + escape(entry.getKey());
            JsonNode value = entry.getValue();
            if (value.isObject()) {
                recordWrite(path, value, candidate, writes);
                if (value.isEmpty() && !isAuthorable(path, authorablePaths)) {
                    throw new UiLayoutResolutionException(
                            UiLayoutResolutionException.Code.PROTECTED_PRESENTATION_INVARIANT,
                            "Layout revision writes a non-authorable presentation path.",
                            path);
                }
                validateAndRecord(value, path, candidate, authorablePaths, removablePaths, writes);
                return;
            }
            if (!isAuthorable(path, authorablePaths)) {
                throw new UiLayoutResolutionException(
                        UiLayoutResolutionException.Code.PROTECTED_PRESENTATION_INVARIANT,
                        "Layout revision writes a non-authorable presentation path.",
                        path);
            }
            if (value.isNull() && (removablePaths == null || !removablePaths.contains(path))) {
                throw new UiLayoutResolutionException(
                        UiLayoutResolutionException.Code.PROTECTED_PRESENTATION_INVARIANT,
                        "Layout revision removes a presentation path that is not removable.",
                        path);
            }
            recordWrite(path, value, candidate, writes);
        });
    }

    private void recordWrite(
            String path,
            JsonNode value,
            UiLayoutResolutionCandidate candidate,
            Map<WriteSlot, Map<String, WriteValue>> writes) {
        WriteSlot slot = new WriteSlot(candidate.layerClass(), candidate.priority());
        Map<String, WriteValue> values = writes.computeIfAbsent(slot, ignored -> new HashMap<>());
        WriteValue current = WriteValue.of(value);
        WriteValue previous = values.get(path);
        if (previous != null && !previous.compatibleWith(current)) ambiguous(path);

        String ancestor = parentPath(path);
        while (ancestor != null) {
            WriteValue ancestorValue = values.get(ancestor);
            if (ancestorValue != null && !ancestorValue.object()) ambiguous(ancestor);
            ancestor = parentPath(ancestor);
        }
        if (!current.object() && values.keySet().stream().anyMatch(existing -> existing.startsWith(path + "/"))) {
            ambiguous(path);
        }
        values.putIfAbsent(path, current);
    }

    private void ambiguous(String path) {
        throw new UiLayoutResolutionException(
                UiLayoutResolutionException.Code.LAYOUT_RESOLUTION_AMBIGUOUS,
                "Matching layout revisions write overlapping values at the same precedence.",
                path);
    }

    private String parentPath(String path) {
        int separator = path.lastIndexOf('/');
        return separator <= 0 ? null : path.substring(0, separator);
    }

    private boolean isAuthorable(String path, Set<String> authorablePaths) {
        if (authorablePaths == null) return false;
        return authorablePaths.stream().anyMatch(allowed -> path.equals(allowed) || path.startsWith(allowed + "/"));
    }

    private void merge(ObjectNode target, ObjectNode patch) {
        UiLayoutMergePatch.apply(target, patch);
    }

    private String escape(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private record WriteSlot(UiLayoutResolutionCandidate.LayerClass layerClass, short priority) {}

    private record WriteValue(boolean object, JsonNode value) {
        private static WriteValue of(JsonNode value) {
            return new WriteValue(value.isObject(), value.isObject() ? null : value.deepCopy());
        }

        private boolean compatibleWith(WriteValue other) {
            return object == other.object && (object || value.equals(other.value));
        }
    }

    record UiLayoutResolutionResult(EffectiveUiLayoutResponse response, List<UiLayoutResolutionCandidate> appliedCandidates) {
        UiLayoutResolutionResult {
            appliedCandidates = List.copyOf(appliedCandidates);
        }
    }
}
