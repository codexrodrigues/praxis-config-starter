package org.praxisplatform.config.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.praxisplatform.config.dto.UiLayoutTarget;

/**
 * Canonical classpath-backed producer of native B0 baseline documents.
 * Component-agnostic: loads static JSON baseline documents from the classpath
 * with a 256KB upper bound, strict UTF-8 decoding, and duplicate-key detection.
 */
public class ClasspathUiLayoutDraftWorkspaceSource implements UiLayoutDraftWorkspaceSource {
    public static final int MAX_BYTES = 256 * 1024;
    private static final String DEFAULT_PATCH_VERSION = "praxis.ui-layout/v1";

    public record BaselinePublication(
            UiLayoutTarget target,
            String resourcePath,
            String documentRef,
            String kind,
            String patchRef,
            String expectedHash,
            String serviceKey,
            String policyRef
    ) {
        public BaselinePublication {
            Objects.requireNonNull(target, "target is required");
            Objects.requireNonNull(resourcePath, "resourcePath is required");
            if (documentRef == null || documentRef.isBlank()) {
                documentRef = "classpath:" + resourcePath;
            }
            if (serviceKey == null || serviceKey.isBlank()) {
                serviceKey = "praxis-platform";
            }
            if (policyRef == null || policyRef.isBlank()) {
                policyRef = "praxis.ui-layout.baseline";
            }
        }

        public BaselinePublication(UiLayoutTarget target, String resourcePath) {
            this(target, resourcePath, null, null, null, null, null, null);
        }

        public BaselinePublication(UiLayoutTarget target, String resourcePath, String expectedHash) {
            this(target, resourcePath, null, null, null, expectedHash, null, null);
        }

        public BaselinePublication(UiLayoutTarget target, String resourcePath, String kind, String patchRef, String expectedHash) {
            this(target, resourcePath, null, kind, patchRef, expectedHash, null, null);
        }
    }

    private final Map<UiLayoutTarget, BaselinePublication> publications = new ConcurrentHashMap<>();
    private final UiLayoutLifecycleAdmission operations;
    private final UiLayoutBaselineMetadataAdmission contentAdmission;
    private final UiLayoutLifecycleStructureValidator structure;
    private final ObjectMapper json;
    private final Clock clock;

    public ClasspathUiLayoutDraftWorkspaceSource(
            UiLayoutLifecycleAdmission operations,
            UiLayoutBaselineMetadataAdmission contentAdmission,
            UiLayoutLifecycleStructureValidator structure) {
        this(operations, contentAdmission, structure, createDefaultObjectMapper(), Clock.systemUTC());
    }

    public ClasspathUiLayoutDraftWorkspaceSource(
            UiLayoutLifecycleAdmission operations,
            UiLayoutBaselineMetadataAdmission contentAdmission,
            UiLayoutLifecycleStructureValidator structure,
            ObjectMapper objectMapper,
            Clock clock) {
        this.operations = operations;
        this.contentAdmission = contentAdmission;
        this.structure = structure;
        this.json = objectMapper != null
                ? objectMapper.copy()
                        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                : createDefaultObjectMapper();
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public ClasspathUiLayoutDraftWorkspaceSource register(BaselinePublication publication) {
        Objects.requireNonNull(publication, "publication is required");
        publications.put(publication.target(), publication);
        return this;
    }

    public ClasspathUiLayoutDraftWorkspaceSource registerAll(Iterable<BaselinePublication> items) {
        if (items != null) {
            for (BaselinePublication item : items) {
                register(item);
            }
        }
        return this;
    }

    public Optional<BaselinePublication> findPublication(UiLayoutTarget target) {
        if (target == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(publications.get(target));
    }

    @Override
    public UiLayoutDraftWorkspaceSeed capture(UiLayoutLifecycleInvocation invocation, UiLayoutValidationContext validation) {
        if (invocation == null) {
            throw failure(UiLayoutLifecycleException.Code.DENIED, "Invocation is required.");
        }
        try {
            validation.require(invocation);
            final UiLayoutLifecycleOperation operation;
            if (validation.purpose() == UiLayoutValidationPurpose.AUTHORING
                    && validation.attempt().operation() == UiLayoutLifecycleOperation.CREATE_DRAFT) {
                operation = UiLayoutLifecycleOperation.CREATE_DRAFT;
            } else if (validation.purpose() == UiLayoutValidationPurpose.EVOLUTION_CURRENT_READ
                    && validation.attempt().operation() == UiLayoutLifecycleOperation.DIAGNOSE_EVOLUTION) {
                operation = UiLayoutLifecycleOperation.DIAGNOSE_EVOLUTION;
            } else {
                throw failure(UiLayoutLifecycleException.Code.DENIED, "Unsupported operation for workspace capture: " + validation.attempt().operation());
            }

            if (operations != null) {
                validation.run(invocation, () -> operations.require(operation, invocation));
            }

            List<UiLayoutTarget> targets = invocation.composition() != null
                    ? invocation.composition().targets()
                    : List.of();
            if (targets.isEmpty()) {
                throw failure(UiLayoutLifecycleException.Code.INVALID_STATE, "Composition must contain at least one target.");
            }

            var seeds = new ArrayList<UiLayoutDraftWorkspaceSeed.TargetSeed>();
            String producer = getClass().getName();

            for (UiLayoutTarget target : targets) {
                if (target == null) {
                    throw failure(UiLayoutLifecycleException.Code.INVALID_STATE, "Target in composition must not be null.");
                }
                BaselinePublication pub = publications.get(target);
                if (pub == null) {
                    throw failure(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE, "No baseline publication registered for target: " + target);
                }

                byte[] bytes = readResource(pub.resourcePath());
                String calculatedHash = sha256(bytes);
                if (pub.expectedHash() != null && !pub.expectedHash().equalsIgnoreCase(calculatedHash)) {
                    throw failure(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE, "Baseline content hash mismatch for " + pub.resourcePath() + ": expected " + pub.expectedHash() + " but calculated " + calculatedHash);
                }

                String raw = decodeUtf8(bytes);
                JsonNode document = json.readTree(raw);
                if (document == null || !document.isObject()) {
                    throw failure(UiLayoutLifecycleException.Code.VALIDATION_FAILED, "Baseline document is not a JSON object: " + pub.resourcePath());
                }

                String kind = pub.kind() != null && !pub.kind().isBlank()
                        ? pub.kind()
                        : document.path("kind").asText("praxis.editor");

                if (!kind.equals(document.path("kind").asText())) {
                    throw failure(UiLayoutLifecycleException.Code.VALIDATION_FAILED, "Baseline document kind mismatch for " + target + ": expected " + kind + " but was " + document.path("kind").asText());
                }

                String version = document.path("version").isIntegralNumber()
                        ? String.valueOf(document.path("version").asInt())
                        : "1";

                var authoring = new UiLayoutAuthoringDocumentDescriptor(kind, kind, version);
                String patchRef = pub.patchRef() != null && !pub.patchRef().isBlank()
                        ? pub.patchRef()
                        : target.componentId() + "-presentation";
                var patch = new UiLayoutPatchDocumentDescriptor(patchRef, DEFAULT_PATCH_VERSION);

                var source = new UiLayoutBaselineMetadataSeed.NativeDocumentSource(
                        pub.serviceKey(),
                        pub.documentRef(),
                        calculatedHash,
                        producer,
                        pub.documentRef() + "#sha256=" + calculatedHash);

                var observation = new UiLayoutBaselineMetadataSeed.Observation(
                        invocation.actorRef() != null ? invocation.actorRef() : "system",
                        invocation.administrativeUnit() != null ? invocation.administrativeUnit() : "root",
                        invocation.contextVersion() != null ? invocation.contextVersion() : "1",
                        pub.policyRef(),
                        "1",
                        Instant.now(clock));

                var metadata = new UiLayoutBaselineMetadataSeed(
                        source,
                        new UiLayoutBaselineMetadataSeed.NativeIdentity(),
                        observation,
                        raw);

                if (contentAdmission != null) {
                    validation.run(invocation, () -> contentAdmission.require(invocation, target, authoring,
                            pub.documentRef(), document.deepCopy(), metadata));
                }
                if (structure != null) {
                    validation.run(invocation, () -> structure.validateAuthoringBaseline(invocation, target,
                            authoring, document.deepCopy(), validation));
                }

                seeds.add(new UiLayoutDraftWorkspaceSeed.TargetSeed(target, authoring, patch,
                        pub.documentRef(), document, metadata));
            }

            if (operations != null) {
                validation.run(invocation, () -> operations.require(operation, invocation));
            }
            return new UiLayoutDraftWorkspaceSeed(seeds);
        } catch (UiLayoutLifecycleException known) {
            throw known;
        } catch (Exception unavailable) {
            throw failure(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE, "Unexpected error reading native workspace: " + unavailable.getMessage());
        }
    }

    private byte[] readResource(String path) {
        String normalized = path != null ? path.trim() : "";
        normalized = normalized.startsWith("classpath:") ? normalized.substring("classpath:".length()) : normalized;
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        InputStream in = openResourceStream(normalized);
        if (in == null) {
            throw failure(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE, "Baseline resource not found on classpath: " + path);
        }
        try (in) {
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw failure(UiLayoutLifecycleException.Code.VALIDATION_FAILED, "Baseline resource exceeds maximum 256KB: " + path);
            }
            return bytes;
        } catch (IOException e) {
            throw failure(UiLayoutLifecycleException.Code.SOURCE_UNAVAILABLE, "Failed to read baseline resource: " + path);
        }
    }

    private InputStream openResourceStream(String normalized) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        InputStream in = cl != null ? cl.getResourceAsStream(normalized) : null;
        if (in == null) {
            in = getClass().getClassLoader().getResourceAsStream(normalized);
        }
        if (in == null) {
            in = getClass().getResourceAsStream("/" + normalized);
        }
        return in;
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static ObjectMapper createDefaultObjectMapper() {
        return new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    private static UiLayoutLifecycleException failure(UiLayoutLifecycleException.Code code, String message) {
        return new UiLayoutLifecycleException(code, message);
    }
}
