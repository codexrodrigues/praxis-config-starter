package org.praxisplatform.config.ai.authoring;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.praxisplatform.config.domain.AiRegistry;
import org.praxisplatform.config.dto.AiRegistryTemplateRecord;
import org.praxisplatform.config.dto.AiRegistryTemplateRevision;
import org.praxisplatform.config.service.AiRegistryTemplateService;
import org.praxisplatform.config.service.CanonicalJsonHashService;

/** Test-scope Java runner for the neutral UiCompositionPlan parity corpus. */
final class UiCompositionGoldenCorpusRunner {

    static final Path DEFAULT_CORPUS = AgenticAuthoringTestPaths.proof(
            "ui-composition-compiler-parity-corpus.v1.json");
    static final Path DEFAULT_SCHEMA = AgenticAuthoringTestPaths.contract(
            "ui-composition-compiler-parity-corpus.v1.schema.json");
    static final Path DEFAULT_REPORT = Path.of(
            "target", "ui-composition-golden", "java-report.json");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CanonicalJsonHashService canonicalHashService =
            new CanonicalJsonHashService(objectMapper);

    public static void main(String[] args) throws Exception {
        Path corpus = argument(args, "--corpus", DEFAULT_CORPUS);
        Path schema = argument(args, "--schema", DEFAULT_SCHEMA);
        Path report = argument(args, "--report", DEFAULT_REPORT);
        ObjectNode result = new UiCompositionGoldenCorpusRunner().run(corpus, schema, report);
        if (!result.path("passed").asBoolean()) {
            System.exit(1);
        }
    }

    ObjectNode run(Path corpusPath, Path schemaPath, Path reportPath) throws Exception {
        byte[] corpusBytes = Files.readAllBytes(corpusPath);
        byte[] schemaBytes = Files.readAllBytes(schemaPath);
        String corpusSha256 = sha256(corpusBytes);
        String schemaSha256 = sha256(schemaBytes);
        try {
            JsonNode corpus = objectMapper.readTree(corpusBytes);
            JsonNode schema = objectMapper.readTree(schemaBytes);
            return run(corpus, schema, reportPath, corpusSha256, schemaSha256);
        } catch (Exception error) {
            ObjectNode report = baseReport(null, corpusSha256, schemaSha256);
            report.put("passed", false);
            report.set("globalFailures", objectMapper.valueToTree(
                    List.of("json-parse:" + readableFailure(error))));
            report.set("cases", objectMapper.createArrayNode());
            writeReport(reportPath, report);
            return report;
        }
    }

    ObjectNode run(JsonNode corpus, JsonNode schema, Path reportPath) throws Exception {
        return run(
                corpus,
                schema,
                reportPath,
                canonicalDocumentSha256(corpus),
                canonicalDocumentSha256(schema));
    }

    private ObjectNode run(
            JsonNode corpus,
            JsonNode schema,
            Path reportPath,
            String corpusSha256,
            String schemaSha256) throws Exception {
        List<String> globalFailures = new ArrayList<>();
        Set<ValidationMessage> schemaErrors = JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(schema)
                .validate(corpus);
        schemaErrors.stream()
                .map(ValidationMessage::getMessage)
                .sorted()
                .forEach(message -> globalFailures.add("schema:" + message));
        verifyTargetProfileReferences(corpus, globalFailures);
        verifyCompilerContract(corpus, globalFailures);
        verifyJavaCompilerReceipt(corpus, globalFailures);

        ArrayNode caseReports = objectMapper.createArrayNode();
        for (JsonNode testCase : corpus.path("cases")) {
            caseReports.add(runCase(testCase));
        }

        boolean casesPassed = true;
        for (JsonNode caseReport : caseReports) {
            casesPassed &= caseReport.path("passed").asBoolean();
        }
        ObjectNode report = baseReport(corpus, corpusSha256, schemaSha256);
        ObjectNode targetProfileFingerprints = objectMapper.createObjectNode();
        corpus.path("targetProfiles").fields().forEachRemaining(entry ->
                targetProfileFingerprints.put(
                        entry.getKey(), entry.getValue().path("registryFingerprint").asText()));
        report.set("targetProfileFingerprints", targetProfileFingerprints);
        report.put("passed", globalFailures.isEmpty() && casesPassed);
        report.set("globalFailures", objectMapper.valueToTree(globalFailures));
        report.set("cases", caseReports);
        writeReport(reportPath, report);
        return report;
    }

    private ObjectNode runCase(JsonNode testCase) throws Exception {
        String caseId = testCase.path("id").asText();
        JsonNode expected = testCase.path("expected").path("compilerParity");
        List<DiagnosticIdentity> diagnostics = new ArrayList<>();
        JsonNode plan = testCase.path("input").path("plan");
        String phase = "compiler-parity";
        String outcome;
        String projectionSha256 = null;
        JsonNode projection = null;

        if (testCase.path("input").has("templateReference")) {
            phase = "template-attestation";
            TemplateResolution resolution = resolveTemplate(testCase.path("input"));
            diagnostics.addAll(resolution.diagnostics());
            if (!resolution.valid()) {
                outcome = "block";
                return caseReport(caseId, phase, outcome, null, null, diagnostics, expected);
            }
            plan = resolution.plan();
            phase = "compiler-parity";
        }

        AgenticAuthoringUiCompositionPlanCompiler compiler =
                new AgenticAuthoringUiCompositionPlanCompiler(objectMapper);
        AgenticAuthoringUiCompositionPlanCompiler.CompileResult result =
                compiler.compile(plan, objectMapper.createObjectNode());
        result.diagnostics().forEach(diagnostic -> diagnostics.add(new DiagnosticIdentity(
                diagnostic.code(),
                diagnostic.path(),
                diagnostic.severity(),
                "compiler")));
        if (result.valid()) {
            outcome = diagnostics.stream().anyMatch(diagnostic -> "warning".equals(diagnostic.severity()))
                    ? "warning"
                    : "pass";
            projection = result.compiledFormPatch().at("/patch/page");
            projectionSha256 = projectionSha256(projection);
        } else {
            outcome = "block";
            Set<String> structuredCodes = result.diagnostics().stream()
                    .map(AgenticAuthoringUiCompositionPlanCompiler.CompilerDiagnostic::code)
                    .collect(java.util.stream.Collectors.toSet());
            result.failureCodes().stream()
                    .filter(code -> !structuredCodes.contains(code))
                    .forEach(code -> diagnostics.add(
                            new DiagnosticIdentity(code, null, "error", "compiler")));
        }
        return caseReport(caseId, phase, outcome, projectionSha256, projection, diagnostics, expected);
    }

    private TemplateResolution resolveTemplate(JsonNode input) throws Exception {
        JsonNode materialization = input.path("templateMaterialization");
        JsonNode revision = materialization.path("revision");
        JsonNode configJson = materialization.path("configJson");
        String actualConfigHash = canonicalHashService.sha256(configJson);
        if (!actualConfigHash.equals(revision.path("configSha256").asText())) {
            return TemplateResolution.invalid(
                    "ui-composition-template-materialization-hash-invalid");
        }

        String registryKey = materialization.path("registryKey").asText();
        AiRegistry registry = AiRegistry.builder()
                .id(UUID.nameUUIDFromBytes(registryKey.getBytes(StandardCharsets.UTF_8)))
                .registryKey(registryKey)
                .status("active")
                .build();
        AiRegistryTemplateRevision storedRevision = AiRegistryTemplateRevision.builder()
                .version(revision.path("version").longValue())
                .etag(revision.path("etag").asText())
                .configSha256(revision.path("configSha256").asText())
                .build();
        AiRegistryTemplateRecord record = AiRegistryTemplateRecord.builder()
                .componentId(registryKey)
                .configJson(configJson)
                .revision(storedRevision)
                .build();
        AiRegistryTemplateService service = mock(AiRegistryTemplateService.class);
        when(service.getTemplate(registryKey)).thenReturn(Optional.of(registry));
        when(service.toRecord(registry)).thenReturn(record);

        AgenticAuthoringUiCompositionTemplateResolver.Resolution result =
                new AgenticAuthoringUiCompositionTemplateResolver(service)
                        .resolve(input.path("templateReference"));
        List<DiagnosticIdentity> diagnostics = new ArrayList<>();
        result.warnings().forEach(code -> diagnostics.add(
                new DiagnosticIdentity(code, null, "warning", "template-attestation")));
        result.failureCodes().forEach(code -> diagnostics.add(
                new DiagnosticIdentity(code, null, "error", "template-attestation")));
        return new TemplateResolution(result.valid(), result.uiCompositionPlan(), diagnostics);
    }

    private ObjectNode caseReport(
            String caseId,
            String phase,
            String outcome,
            String projectionSha256,
            JsonNode projection,
            List<DiagnosticIdentity> diagnostics,
            JsonNode expected) {
        List<String> failures = new ArrayList<>();
        if (!expected.path("phase").asText().equals(phase)) {
            failures.add("phase-mismatch");
        }
        if (!expected.path("outcome").asText().equals(outcome)) {
            failures.add("outcome-mismatch");
        }
        String expectedProjectionSha256 = expected.path("projectionSha256").asText(null);
        if (!java.util.Objects.equals(expectedProjectionSha256, projectionSha256)) {
            failures.add("projection-sha-mismatch");
        }

        List<DiagnosticIdentity> expectedDiagnostics = expectedDiagnostics(expected);
        if (!expectedDiagnostics.equals(diagnostics)) {
            failures.add("diagnostics-mismatch");
        }

        ObjectNode report = objectMapper.createObjectNode();
        report.put("caseId", caseId);
        report.put("phase", phase);
        report.put("outcome", outcome);
        if (projectionSha256 != null) {
            report.put("projectionSha256", projectionSha256);
        }
        if (projection != null) {
            report.set("projection", projection);
        }
        report.set("diagnostics", objectMapper.valueToTree(diagnostics));
        report.set("canonicalDiagnostics", objectMapper.valueToTree(
                canonicalDiagnostics(diagnostics, expected)));
        report.put("passed", failures.isEmpty());
        report.set("failures", objectMapper.valueToTree(failures));
        return report;
    }

    private List<DiagnosticIdentity> expectedDiagnostics(JsonNode expected) {
        List<DiagnosticIdentity> identities = new ArrayList<>();
        for (JsonNode diagnostic : expected.path("diagnostics")) {
            identities.add(new DiagnosticIdentity(
                    diagnostic.at("/engineCodes/java").asText(),
                    diagnostic.at("/pathByEngine/java").isNull()
                            ? null
                            : diagnostic.at("/pathByEngine/java").asText(),
                    diagnostic.path("severity").asText(),
                    diagnostic.path("provenance").asText()));
        }
        return identities;
    }

    private List<CanonicalDiagnosticIdentity> canonicalDiagnostics(
            List<DiagnosticIdentity> diagnostics,
            JsonNode expected) {
        List<CanonicalDiagnosticIdentity> identities = new ArrayList<>();
        for (DiagnosticIdentity actual : diagnostics) {
            String canonicalId = null;
            for (JsonNode candidate : expected.path("diagnostics")) {
                DiagnosticIdentity expectedIdentity = new DiagnosticIdentity(
                        candidate.at("/engineCodes/java").asText(),
                        candidate.at("/pathByEngine/java").isNull()
                                ? null
                                : candidate.at("/pathByEngine/java").asText(),
                        candidate.path("severity").asText(),
                        candidate.path("provenance").asText());
                if (expectedIdentity.equals(actual)) {
                    canonicalId = candidate.path("canonicalId").asText();
                    break;
                }
            }
            if (canonicalId == null) {
                canonicalId = "unmapped:" + actual.code() + ":" + String.valueOf(actual.path());
            }
            identities.add(new CanonicalDiagnosticIdentity(
                    canonicalId, actual.severity(), actual.provenance()));
        }
        return identities;
    }

    private void verifyTargetProfileReferences(JsonNode corpus, List<String> failures) {
        JsonNode profiles = corpus.path("targetProfiles");
        for (JsonNode testCase : corpus.path("cases")) {
            String profileId = testCase.at("/expected/targetAttestation/profileId").asText();
            if (!profiles.has(profileId)) {
                failures.add("target-profile-missing:"
                        + testCase.path("id").asText()
                        + ":"
                        + profileId);
            }
        }
    }

    private void verifyCompilerContract(JsonNode corpus, List<String> failures) {
        JsonNode contract = corpus.at("/compilerContracts/java");
        if (!"config-ui-composition-plan-compiler".equals(contract.path("id").asText())) {
            failures.add("java-compiler-id-mismatch");
        }
        if (!AgenticAuthoringUiCompositionPlanCompiler.BUILDER_VERSION.equals(
                contract.path("builderVersion").asText())) {
            failures.add("java-compiler-builder-version-mismatch");
        }
    }

    private void verifyJavaCompilerReceipt(JsonNode corpus, List<String> failures) {
        JsonNode receipt = corpus.at("/compilerReceipts/java");
        String expectedSourcePath =
                "src/main/java/org/praxisplatform/config/ai/authoring/"
                        + "AgenticAuthoringUiCompositionPlanCompiler.java";
        String expectedArtifactPath =
                "target/classes/org/praxisplatform/config/ai/authoring/"
                        + "AgenticAuthoringUiCompositionPlanCompiler.class";
        if (!"source-git-blob-and-compiled-class-bytes".equals(receipt.path("scope").asText())) {
            failures.add("java-compiler-receipt-scope-mismatch");
        }
        if (!expectedSourcePath.equals(receipt.path("sourcePath").asText())) {
            failures.add("java-compiler-receipt-source-path-mismatch");
        }
        if (!expectedArtifactPath.equals(receipt.path("artifactPath").asText())) {
            failures.add("java-compiler-receipt-artifact-path-mismatch");
        }
        try {
            Path sourcePath = Path.of(expectedSourcePath);
            if (!Files.isRegularFile(sourcePath)) {
                failures.add("java-compiler-receipt-source-missing");
            } else {
                String actualBlob = gitHashObject(sourcePath);
                if (!actualBlob.equals(receipt.path("sourceGitBlob").asText())) {
                    failures.add("java-compiler-receipt-source-blob-mismatch");
                }
            }
            Path artifactPath = Path.of(expectedArtifactPath);
            if (!Files.isRegularFile(artifactPath)) {
                failures.add("java-compiler-receipt-artifact-missing");
            } else if (!sha256(Files.readAllBytes(artifactPath))
                    .equals(receipt.path("artifactSha256").asText())) {
                failures.add("java-compiler-receipt-artifact-sha-mismatch");
            }
        } catch (Exception error) {
            failures.add("java-compiler-receipt-verification:" + readableFailure(error));
        }
    }

    private String gitHashObject(Path sourcePath) throws Exception {
        Process process = new ProcessBuilder("git", "hash-object", sourcePath.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.waitFor() != 0 || !output.matches("[0-9a-f]{40}")) {
            throw new IllegalStateException("git hash-object failed: " + output);
        }
        return output;
    }

    private String compilerImplementationSha256() throws Exception {
        String resource = "/"
                + AgenticAuthoringUiCompositionPlanCompiler.class.getName().replace('.', '/')
                + ".class";
        try (java.io.InputStream input = AgenticAuthoringUiCompositionPlanCompiler.class
                .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Compiler bytecode resource not found: " + resource);
            }
            return sha256(input.readAllBytes());
        }
    }

    private ObjectNode baseReport(
            JsonNode corpus,
            String corpusSha256,
            String schemaSha256) throws Exception {
        ObjectNode report = objectMapper.createObjectNode();
        report.put("schemaVersion", "praxis.ui-composition-golden-report/v1");
        if (corpus == null || corpus.path("corpusVersion").isMissingNode()) {
            report.putNull("corpusVersion");
        } else {
            report.put("corpusVersion", corpus.path("corpusVersion").asText());
        }
        report.put("corpusSha256", corpusSha256);
        report.put("schemaSha256", schemaSha256);
        report.put("engine", "java");
        ObjectNode compilerIdentity = report.putObject("compilerIdentity");
        compilerIdentity.put(
                "id",
                corpus == null
                        ? "config-ui-composition-plan-compiler"
                        : corpus.at("/compilerContracts/java/id").asText());
        compilerIdentity.put("builderVersion", AgenticAuthoringUiCompositionPlanCompiler.BUILDER_VERSION);
        String implementationSha256 = compilerImplementationSha256();
        compilerIdentity.put("implementationSha256", implementationSha256);
        ObjectNode implementationArtifact = compilerIdentity.putObject("implementationArtifact");
        implementationArtifact.put("kind", "jvm-class");
        implementationArtifact.put(
                "path",
                "target/classes/org/praxisplatform/config/ai/authoring/"
                        + "AgenticAuthoringUiCompositionPlanCompiler.class");
        implementationArtifact.put("sha256", implementationSha256);
        if (corpus != null && corpus.at("/compilerReceipts/java").isObject()) {
            compilerIdentity.set(
                    "sourceReceipt",
                    corpus.at("/compilerReceipts/java").deepCopy());
        }
        return report;
    }

    private String readableFailure(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        String normalized = message.replaceAll("[\\r\\n]+", " ");
        return normalized.length() > 240 ? normalized.substring(0, 240) : normalized;
    }

    private String canonicalDocumentSha256(JsonNode document) throws Exception {
        return sha256(objectMapper.writeValueAsBytes(canonicalProjection(document)));
    }

    private String projectionSha256(JsonNode page) throws Exception {
        return canonicalDocumentSha256(page);
    }

    private String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        return HexFormat.of().formatHex(digest);
    }

    private JsonNode canonicalProjection(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());
            names.forEach(name -> result.set(name, canonicalProjection(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(value -> result.add(canonicalProjection(value)));
            return result;
        }
        return node.deepCopy();
    }

    private static Path argument(String[] args, String name, Path fallback) {
        for (int index = 0; index < args.length - 1; index++) {
            if (name.equals(args[index])) {
                return Path.of(args[index + 1]);
            }
        }
        return fallback;
    }

    private void writeReport(Path reportPath, JsonNode report) throws Exception {
        Files.createDirectories(reportPath.toAbsolutePath().getParent());
        Files.writeString(
                reportPath,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n",
                StandardCharsets.UTF_8);
    }

    record DiagnosticIdentity(String code, String path, String severity, String provenance) {}

    record CanonicalDiagnosticIdentity(String canonicalId, String severity, String provenance) {}

    record TemplateResolution(boolean valid, JsonNode plan, List<DiagnosticIdentity> diagnostics) {
        static TemplateResolution invalid(String code) {
            return new TemplateResolution(
                    false,
                    null,
                    List.of(new DiagnosticIdentity(
                            code, null, "error", "template-attestation")));
        }
    }
}
