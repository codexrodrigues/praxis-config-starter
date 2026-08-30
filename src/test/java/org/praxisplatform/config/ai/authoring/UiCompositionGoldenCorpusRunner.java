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
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
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
import java.util.TreeSet;
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
    static final Path DEFAULT_REPORT_SCHEMA = AgenticAuthoringTestPaths.contract(
            "ui-composition-golden-report.v1.schema.json");
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
        validateReportShape(report);
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
        if (!"ordered-praxis-source-and-class-byte-closure".equals(receipt.path("scope").asText())) {
            failures.add("java-compiler-receipt-scope-mismatch");
        }
        try {
            ObjectNode actual = javaExecutionClosureReceipt();
            JsonNode declaredSources = receipt.path("sources");
            JsonNode actualSources = actual.path("sources");
            if (!declaredSources.isArray() || declaredSources.size() != actualSources.size()) {
                failures.add("java-compiler-receipt-source-closure-mismatch");
            } else {
                for (int sourceIndex = 0; sourceIndex < actualSources.size(); sourceIndex++) {
                    JsonNode declaredSource = declaredSources.get(sourceIndex);
                    JsonNode actualSource = actualSources.get(sourceIndex);
                    String sourceId = actualSource.path("id").asText();
                    if (!sourceId.equals(declaredSource.path("id").asText())) {
                        failures.add("java-compiler-receipt-source-id-mismatch:" + sourceId);
                    }
                    if (!actualSource.path("sourcePath").asText()
                            .equals(declaredSource.path("sourcePath").asText())) {
                        failures.add("java-compiler-receipt-source-path-mismatch:" + sourceId);
                    }
                    if (!actualSource.path("sourceGitBlob").asText()
                            .equals(declaredSource.path("sourceGitBlob").asText())) {
                        failures.add("java-compiler-receipt-source-blob-mismatch:" + sourceId);
                    }
                    JsonNode declaredArtifacts = declaredSource.path("artifacts");
                    JsonNode actualArtifacts = actualSource.path("artifacts");
                    if (!declaredArtifacts.isArray()
                            || declaredArtifacts.size() != actualArtifacts.size()) {
                        failures.add("java-compiler-receipt-artifact-closure-mismatch:" + sourceId);
                        continue;
                    }
                    for (int artifactIndex = 0; artifactIndex < actualArtifacts.size(); artifactIndex++) {
                        JsonNode declaredArtifact = declaredArtifacts.get(artifactIndex);
                        JsonNode actualArtifact = actualArtifacts.get(artifactIndex);
                        String artifactPath = actualArtifact.path("path").asText();
                        if (!artifactPath.equals(declaredArtifact.path("path").asText())) {
                            failures.add("java-compiler-receipt-artifact-path-mismatch:"
                                    + sourceId + ":" + artifactIndex);
                        }
                        if (!actualArtifact.path("sha256").asText()
                                .equals(declaredArtifact.path("sha256").asText())) {
                            failures.add("java-compiler-receipt-artifact-sha-mismatch:" + artifactPath);
                        }
                    }
                }
            }
            if (!actual.path("closureSha256").asText()
                    .equals(receipt.path("closureSha256").asText())) {
                failures.add("java-compiler-receipt-closure-sha-mismatch");
            }
            if (!actual.path("dependencyGraph").equals(receipt.path("dependencyGraph"))) {
                failures.add("java-compiler-receipt-dependency-graph-mismatch");
            }
            Set<String> coveredClasses = new TreeSet<>();
            actual.path("sources").forEach(source -> source.path("artifacts").forEach(artifact ->
                    coveredClasses.add(binaryClassName(artifact.path("path").asText()))));
            actual.path("dependencyGraph").forEach(edge -> {
                String value = edge.asText();
                int separator = value.indexOf(" -> ");
                String target = separator < 0 ? "" : value.substring(separator + 4);
                if (!coveredClasses.contains(target)) {
                    failures.add("java-compiler-receipt-uncovered-praxis-dependency:" + target);
                }
            });
        } catch (Exception error) {
            failures.add("java-compiler-receipt-verification:" + readableFailure(error));
        }
    }

    private ObjectNode javaExecutionClosureReceipt() throws Exception {
        ObjectNode receipt = objectMapper.createObjectNode();
        receipt.put("scope", "ordered-praxis-source-and-class-byte-closure");
        ArrayNode sources = receipt.putArray("sources");
        addJavaExecutionSource(
                sources,
                "golden-corpus-runner",
                "src/test/java/org/praxisplatform/config/ai/authoring/UiCompositionGoldenCorpusRunner.java",
                List.of(
                        "target/test-classes/org/praxisplatform/config/ai/authoring/UiCompositionGoldenCorpusRunner.class",
                        "target/test-classes/org/praxisplatform/config/ai/authoring/UiCompositionGoldenCorpusRunner$CanonicalDiagnosticIdentity.class",
                        "target/test-classes/org/praxisplatform/config/ai/authoring/UiCompositionGoldenCorpusRunner$DiagnosticIdentity.class",
                        "target/test-classes/org/praxisplatform/config/ai/authoring/UiCompositionGoldenCorpusRunner$TemplateResolution.class"));
        addJavaExecutionSource(
                sources,
                "agentic-authoring-test-paths",
                "src/test/java/org/praxisplatform/config/ai/authoring/AgenticAuthoringTestPaths.java",
                List.of("target/test-classes/org/praxisplatform/config/ai/authoring/AgenticAuthoringTestPaths.class"));
        addJavaExecutionSource(
                sources,
                "ui-composition-plan-compiler",
                "src/main/java/org/praxisplatform/config/ai/authoring/AgenticAuthoringUiCompositionPlanCompiler.java",
                List.of(
                        "target/classes/org/praxisplatform/config/ai/authoring/AgenticAuthoringUiCompositionPlanCompiler.class",
                        "target/classes/org/praxisplatform/config/ai/authoring/AgenticAuthoringUiCompositionPlanCompiler$CompileResult.class",
                        "target/classes/org/praxisplatform/config/ai/authoring/AgenticAuthoringUiCompositionPlanCompiler$CompilerDiagnostic.class",
                        "target/classes/org/praxisplatform/config/ai/authoring/AgenticAuthoringUiCompositionPlanCompiler$MasterDetailAnalysis.class",
                        "target/classes/org/praxisplatform/config/ai/authoring/AgenticAuthoringUiCompositionPlanCompiler$MasterDetailWidget.class",
                        "target/classes/org/praxisplatform/config/ai/authoring/AgenticAuthoringUiCompositionPlanCompiler$NestedWidgetArrayLocation.class",
                        "target/classes/org/praxisplatform/config/ai/authoring/AgenticAuthoringUiCompositionPlanCompiler$SlotDefinition.class",
                        "target/classes/org/praxisplatform/config/ai/authoring/AgenticAuthoringUiCompositionPlanCompiler$UiCompositionLayoutMaterialization.class"));
        addJavaExecutionSource(
                sources,
                "compiled-page-patch-validator",
                "src/main/java/org/praxisplatform/config/ai/authoring/AgenticAuthoringCompiledPagePatchValidator.java",
                List.of("target/classes/org/praxisplatform/config/ai/authoring/AgenticAuthoringCompiledPagePatchValidator.class"));
        addJavaExecutionSource(
                sources,
                "ui-composition-template-resolver",
                "src/main/java/org/praxisplatform/config/ai/authoring/AgenticAuthoringUiCompositionTemplateResolver.java",
                List.of(
                        "target/classes/org/praxisplatform/config/ai/authoring/AgenticAuthoringUiCompositionTemplateResolver.class",
                        "target/classes/org/praxisplatform/config/ai/authoring/AgenticAuthoringUiCompositionTemplateResolver$Resolution.class"));
        addJavaExecutionSource(
                sources,
                "canonical-json-hash-service",
                "src/main/java/org/praxisplatform/config/service/CanonicalJsonHashService.java",
                List.of("target/classes/org/praxisplatform/config/service/CanonicalJsonHashService.class"));
        addJavaExecutionSource(
                sources,
                "ai-registry",
                "src/main/java/org/praxisplatform/config/domain/AiRegistry.java",
                List.of(
                        "target/classes/org/praxisplatform/config/domain/AiRegistry.class",
                        "target/classes/org/praxisplatform/config/domain/AiRegistry$AiRegistryBuilder.class"));
        addJavaExecutionSource(
                sources,
                "ai-registry-template-record",
                "src/main/java/org/praxisplatform/config/dto/AiRegistryTemplateRecord.java",
                List.of(
                        "target/classes/org/praxisplatform/config/dto/AiRegistryTemplateRecord.class",
                        "target/classes/org/praxisplatform/config/dto/AiRegistryTemplateRecord$AiRegistryTemplateRecordBuilder.class"));
        addJavaExecutionSource(
                sources,
                "ai-registry-template-revision",
                "src/main/java/org/praxisplatform/config/dto/AiRegistryTemplateRevision.java",
                List.of(
                        "target/classes/org/praxisplatform/config/dto/AiRegistryTemplateRevision.class",
                        "target/classes/org/praxisplatform/config/dto/AiRegistryTemplateRevision$AiRegistryTemplateRevisionBuilder.class"));
        addJavaExecutionSource(
                sources,
                "ai-registry-template-service-mock-boundary",
                "src/main/java/org/praxisplatform/config/service/AiRegistryTemplateService.java",
                List.of("target/classes/org/praxisplatform/config/service/AiRegistryTemplateService.class"));
        addJavaExecutionSource(
                sources,
                "ai-registry-scope",
                "src/main/java/org/praxisplatform/config/domain/Scope.java",
                List.of("target/classes/org/praxisplatform/config/domain/Scope.class"));
        ArrayNode dependencyGraph = javaPraxisDependencyGraph(sources);
        receipt.set("dependencyGraph", dependencyGraph);
        ObjectNode closureMaterial = objectMapper.createObjectNode();
        closureMaterial.set("sources", sources.deepCopy());
        closureMaterial.set("dependencyGraph", dependencyGraph.deepCopy());
        receipt.put("closureSha256", canonicalDocumentSha256(closureMaterial));
        return receipt;
    }

    private void addJavaExecutionSource(
            ArrayNode sources,
            String id,
            String sourcePathValue,
            List<String> artifactPaths) throws Exception {
        Path sourcePath = Path.of(sourcePathValue);
        if (!Files.isRegularFile(sourcePath)) {
            throw new IllegalStateException("Java execution source missing: " + sourcePathValue);
        }
        ObjectNode source = sources.addObject();
        source.put("id", id);
        source.put("sourcePath", sourcePathValue);
        source.put("sourceGitBlob", gitHashObject(sourcePath));
        ArrayNode artifacts = source.putArray("artifacts");
        for (String artifactPathValue : artifactPaths) {
            Path artifactPath = Path.of(artifactPathValue);
            if (!Files.isRegularFile(artifactPath)) {
                throw new IllegalStateException("Java execution artifact missing: " + artifactPathValue);
            }
            ObjectNode artifact = artifacts.addObject();
            artifact.put("path", artifactPathValue);
            artifact.put("sha256", sha256(Files.readAllBytes(artifactPath)));
        }
    }

    private ArrayNode javaPraxisDependencyGraph(ArrayNode sources) throws Exception {
        String mockBoundary =
                "target/classes/org/praxisplatform/config/service/AiRegistryTemplateService.class";
        Set<String> edges = new TreeSet<>();
        for (JsonNode source : sources) {
            for (JsonNode artifact : source.path("artifacts")) {
                String artifactPath = artifact.path("path").asText();
                if (mockBoundary.equals(artifactPath)) {
                    continue;
                }
                String from = binaryClassName(artifactPath);
                for (String target : praxisClassReferences(Files.readAllBytes(Path.of(artifactPath)))) {
                    if (!from.equals(target)) {
                        edges.add(from + "\u0000" + target);
                    }
                }
            }
        }
        ArrayNode graph = objectMapper.createArrayNode();
        for (String edge : edges) {
            int separator = edge.indexOf('\u0000');
            graph.add(edge.substring(0, separator) + " -> " + edge.substring(separator + 1));
        }
        return graph;
    }

    private Set<String> praxisClassReferences(byte[] bytecode) throws Exception {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytecode))) {
            if (input.readInt() != 0xCAFEBABE) {
                throw new IllegalArgumentException("Invalid JVM class artifact");
            }
            input.readUnsignedShort();
            input.readUnsignedShort();
            int constantPoolCount = input.readUnsignedShort();
            String[] utf8 = new String[constantPoolCount];
            int[] classNameIndexes = new int[constantPoolCount];
            for (int index = 1; index < constantPoolCount; index++) {
                int tag = input.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[index] = input.readUTF();
                    case 3, 4 -> input.skipNBytes(4);
                    case 5, 6 -> {
                        input.skipNBytes(8);
                        index++;
                    }
                    case 7 -> classNameIndexes[index] = input.readUnsignedShort();
                    case 8, 16, 19, 20 -> input.skipNBytes(2);
                    case 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
                    case 15 -> input.skipNBytes(3);
                    default -> throw new IllegalArgumentException("Unsupported JVM constant tag: " + tag);
                }
            }
            Set<String> references = new TreeSet<>();
            for (int classNameIndex : classNameIndexes) {
                if (classNameIndex <= 0 || classNameIndex >= utf8.length) {
                    continue;
                }
                String internalName = utf8[classNameIndex];
                if (internalName == null) {
                    continue;
                }
                if (internalName.startsWith("[L") && internalName.endsWith(";")) {
                    internalName = internalName.substring(2, internalName.length() - 1);
                }
                if (internalName.startsWith("org/praxisplatform/config/")) {
                    references.add(internalName.replace('/', '.'));
                }
            }
            input.skipNBytes(6);
            int interfaceCount = input.readUnsignedShort();
            input.skipNBytes((long) interfaceCount * 2);
            readMemberDescriptorReferences(input, utf8, references);
            readMemberDescriptorReferences(input, utf8, references);
            return references;
        }
    }

    private void readMemberDescriptorReferences(
            DataInputStream input,
            String[] utf8,
            Set<String> references) throws Exception {
        int memberCount = input.readUnsignedShort();
        for (int memberIndex = 0; memberIndex < memberCount; memberIndex++) {
            input.readUnsignedShort();
            input.readUnsignedShort();
            int descriptorIndex = input.readUnsignedShort();
            addDescriptorReferences(utf8[descriptorIndex], references);
            int attributeCount = input.readUnsignedShort();
            for (int attributeIndex = 0; attributeIndex < attributeCount; attributeIndex++) {
                input.readUnsignedShort();
                long length = Integer.toUnsignedLong(input.readInt());
                input.skipNBytes(length);
            }
        }
    }

    private void addDescriptorReferences(String descriptor, Set<String> references) {
        int cursor = 0;
        String marker = "Lorg/praxisplatform/config/";
        while (descriptor != null && (cursor = descriptor.indexOf(marker, cursor)) >= 0) {
            int end = descriptor.indexOf(';', cursor);
            if (end < 0) {
                return;
            }
            references.add(descriptor.substring(cursor + 1, end).replace('/', '.'));
            cursor = end + 1;
        }
    }

    private String binaryClassName(String artifactPath) {
        int classesMarker = artifactPath.indexOf("classes/");
        if (classesMarker < 0 || !artifactPath.endsWith(".class")) {
            throw new IllegalArgumentException("Unexpected Java artifact path: " + artifactPath);
        }
        return artifactPath
                .substring(classesMarker + "classes/".length(), artifactPath.length() - ".class".length())
                .replace('/', '.');
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
        report.put("reportSchemaSha256", sha256(Files.readAllBytes(DEFAULT_REPORT_SCHEMA)));
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
        compilerIdentity.set("sourceReceipt", javaExecutionClosureReceipt());
        return report;
    }

    private void validateReportShape(ObjectNode report) throws Exception {
        JsonNode reportSchema = objectMapper.readTree(DEFAULT_REPORT_SCHEMA.toFile());
        List<String> shapeFailures = JsonSchemaFactory
                .getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(reportSchema)
                .validate(report)
                .stream()
                .map(ValidationMessage::getMessage)
                .sorted()
                .map(message -> "report-schema:" + message)
                .toList();
        if (!shapeFailures.isEmpty()) {
            ArrayNode failures = (ArrayNode) report.withArray("globalFailures");
            shapeFailures.forEach(failures::add);
            report.put("passed", false);
        }
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
