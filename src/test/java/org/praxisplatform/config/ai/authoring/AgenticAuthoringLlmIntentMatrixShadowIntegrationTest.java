package org.praxisplatform.config.ai.authoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.service.AiCallConfig;
import org.praxisplatform.config.service.AiJsonSchema;
import org.praxisplatform.config.service.AiProvider;
import org.praxisplatform.config.service.SpringAiGeminiService;
import org.praxisplatform.config.service.SpringAiOpenAiService;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

@Tag("external")
@Tag("e2e")
class AgenticAuthoringLlmIntentMatrixShadowIntegrationTest {

    private static final String ENABLE_ENV = "PRAXIS_AGENTIC_AUTHORING_LLM_INTENT_MATRIX_SHADOW";
    private static final String TARGET_APP = "praxis-ui-angular";
    private static final String TARGET_COMPONENT = "praxis-dynamic-page-builder";
    private static final Set<String> MATRIX_STATUSES = Set.of("covered");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldClassifyIntentAccuracyMatrixAgainstComponentCapabilities() throws Exception {
        assumeTrue("true".equalsIgnoreCase(env(ENABLE_ENV)),
                "Set " + ENABLE_ENV + "=true to run this external LLM intent matrix shadow test.");

        String providerName = normalize(envOrDefault("PRAXIS_AGENTIC_AUTHORING_SHADOW_PROVIDER", "openai"));
        AiProvider provider = createProvider(providerName);

        JsonNode matrix = objectMapper.readTree(Files.readString(proofsDir().resolve("intent-accuracy-matrix.v0.json")));
        List<MatrixCase> cases = matrixCases(matrix);
        JsonNode capabilities = objectMapper.valueToTree(new AgenticAuthoringComponentCapabilitiesService().listCapabilities());
        JsonNode llmResult = provider.generateJson(
                shadowPrompt(providerName, cases, capabilities),
                AiJsonSchema.ofSchema(Files.readString(contractsDir().resolve("llm-shadow-intent-matrix-result.v1.schema.json"))),
                callConfig(providerName));

        assertLlmResultShape(llmResult, cases);

        ArrayNode reportCases = objectMapper.createArrayNode();
        int exactMatches = 0;
        int divergentCases = 0;
        for (MatrixCase matrixCase : cases) {
            JsonNode llmCase = findLlmCase(llmResult.path("cases"), matrixCase.id());
            AgenticAuthoringIntentResolutionResult deterministic = deterministicResolve(matrixCase);
            ObjectNode reportCase = reportCase(providerName, matrixCase, llmCase, deterministic, capabilities);
            if (reportCase.path("divergences").isEmpty()) {
                exactMatches++;
            } else {
                divergentCases++;
            }
            reportCases.add(reportCase);
        }

        writeReport(providerName, cases.size(), exactMatches, divergentCases, reportCases);

        if (Boolean.parseBoolean(envOrDefault("PRAXIS_AGENTIC_AUTHORING_SHADOW_MATRIX_FAIL_ON_DIVERGENCE", "false"))) {
            assertThat(divergentCases).isZero();
        }
    }

    private List<MatrixCase> matrixCases(JsonNode matrix) {
        List<MatrixCase> cases = new ArrayList<>();
        for (JsonNode node : matrix.path("cases")) {
            String status = text(node, "status");
            if (!MATRIX_STATUSES.contains(status)) {
                continue;
            }
            JsonNode expected = node.path("expected");
            cases.add(new MatrixCase(
                    text(node, "id"),
                    text(node, "userPrompt"),
                    text(expected, "operationKind"),
                    text(expected, "artifactKind"),
                    text(expected, "changeKind"),
                    nullableText(expected, "resourcePath"),
                    expected.path("requiresClarification").asBoolean(false),
                    nullableText(expected, "clarificationCode"),
                    status));
        }
        return cases;
    }

    private String shadowPrompt(String providerName, List<MatrixCase> cases, JsonNode capabilities) throws Exception {
        ObjectNode promptPayload = objectMapper.createObjectNode();
        promptPayload.set("componentCapabilities", capabilities);
        promptPayload.set("resources", resourceCatalog());
        ArrayNode caseNodes = promptPayload.putArray("cases");
        for (MatrixCase matrixCase : cases) {
            ObjectNode node = caseNodes.addObject();
            node.put("id", matrixCase.id());
            node.put("userPrompt", matrixCase.userPrompt());
            node.set("runtimeContext", runtimeContext(matrixCase));
        }

        return """
                You are classifying Praxis Page Builder assistant user intent in a live shadow evaluation.
                Return only one JSON object matching the provided schema. Do not include Markdown.

                Use the declarative component capability catalogs as the source of allowed editable changeKind values.
                For prompts with multiple requested chart edits, put the pipe-separated ordered changeKind sequence in changeKind.
                For fieldsUsed, list business fields, columns, dimensions, metrics, formats, or component fields used to choose the intent.
                If a prompt lacks enough action/resource context, use operationKind "unknown", artifactKind "unknown",
                changeKind "unknown", requiresClarification true, and the best clarificationCode.

                Do not invent endpoints. Allowed resource paths are only:
                - /api/human-resources/vw-analytics-folha-pagamento
                - /api/human-resources/folhas-pagamento
                - /api/human-resources/funcionarios
                - null when no real resource is confirmed.

                Provider under test: %s.

                Input payload:
                %s
                """.formatted(providerName, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(promptPayload));
    }

    private ArrayNode resourceCatalog() {
        ArrayNode resources = objectMapper.createArrayNode();
        resources.addObject()
                .put("resourcePath", "/api/human-resources/vw-analytics-folha-pagamento")
                .put("operation", "get")
                .put("artifactKinds", "dashboard")
                .put("description", "Payroll analytics for dashboards grouped by department or competence.");
        resources.addObject()
                .put("resourcePath", "/api/human-resources/folhas-pagamento")
                .put("operation", "get")
                .put("artifactKinds", "table")
                .put("description", "Operational payroll records suitable for table/list views.");
        resources.addObject()
                .put("resourcePath", "/api/human-resources/funcionarios")
                .put("operation", "post")
                .put("artifactKinds", "form")
                .put("description", "Employee registration form resource.");
        return resources;
    }

    private ObjectNode runtimeContext(MatrixCase matrixCase) {
        ObjectNode context = objectMapper.createObjectNode();
        String id = matrixCase.id();
        if (id.startsWith("payroll-table-")) {
            context.put("selectedWidgetKey", "payroll-table");
            context.put("selectedComponentId", "praxis-table");
            context.put("resourcePath", "/api/human-resources/folhas-pagamento");
            context.putArray("availableFields").add("id").add("salarioLiquido").add("salarioBruto").add("totalDescontos");
        } else if (id.startsWith("payroll-chart-")) {
            context.put("selectedWidgetKey", "payroll-chart");
            context.put("selectedComponentId", "praxis-chart");
            context.put("resourcePath", "/api/human-resources/vw-analytics-folha-pagamento");
            context.putArray("availableFields").add("departamento").add("competencia").add("salarioLiquido").add("salarioBruto");
        } else if (id.startsWith("employee-form-") && !id.equals("employee-form-create")) {
            context.put("selectedWidgetKey", "funcionarios-form");
            context.put("selectedComponentId", "praxis-dynamic-form");
            context.put("resourcePath", "/api/human-resources/funcionarios");
            context.putArray("availableFields").add("nome").add("nomeCompleto").add("observacaoInterna");
            context.putArray("localTransientFields").add("observacaoInterna");
        } else {
            context.putNull("selectedWidgetKey");
        }
        return context;
    }

    private AgenticAuthoringIntentResolutionResult deterministicResolve(MatrixCase matrixCase) {
        return new AgenticAuthoringIntentResolverService(objectMapper).resolve(new AgenticAuthoringIntentResolutionRequest(
                matrixCase.userPrompt(),
                TARGET_APP,
                TARGET_COMPONENT,
                "/page-builder-ia",
                currentPage(matrixCase),
                selectedWidgetKey(matrixCase),
                null,
                null,
                null));
    }

    private JsonNode currentPage(MatrixCase matrixCase) {
        String id = matrixCase.id();
        if (id.startsWith("payroll-table-")) {
            return payrollTablePage();
        }
        if (id.startsWith("payroll-chart-")) {
            return payrollChartPage();
        }
        if (id.startsWith("employee-form-") && !id.equals("employee-form-create")) {
            return employeeFormPage();
        }
        return objectMapper.createObjectNode();
    }

    private String selectedWidgetKey(MatrixCase matrixCase) {
        String id = matrixCase.id();
        if (id.startsWith("payroll-table-")) {
            return "payroll-table";
        }
        if (id.startsWith("payroll-chart-")) {
            return "payroll-chart";
        }
        if (id.startsWith("employee-form-") && !id.equals("employee-form-create")) {
            return "funcionarios-form";
        }
        return null;
    }

    private ObjectNode reportCase(
            String providerName,
            MatrixCase matrixCase,
            JsonNode llmCase,
            AgenticAuthoringIntentResolutionResult deterministic,
            JsonNode capabilities) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", matrixCase.id());
        node.put("provider", providerName);
        node.put("status", matrixCase.status());
        node.put("userPromptHash", Integer.toHexString(matrixCase.userPrompt().hashCode()));

        ObjectNode expected = node.putObject("expected");
        expected.put("operationKind", matrixCase.operationKind());
        expected.put("artifactKind", matrixCase.artifactKind());
        expected.put("changeKind", matrixCase.changeKind());
        putNullable(expected, "resourcePath", matrixCase.resourcePath());
        expected.put("requiresClarification", matrixCase.requiresClarification());
        putNullable(expected, "clarificationCode", matrixCase.clarificationCode());

        ObjectNode actual = node.putObject("llmActual");
        actual.put("operationKind", text(llmCase, "operationKind"));
        actual.put("artifactKind", text(llmCase, "artifactKind"));
        actual.put("changeKind", text(llmCase, "changeKind"));
        putNullable(actual, "resourcePath", nullableText(llmCase, "resourcePath"));
        actual.put("requiresClarification", llmCase.path("requiresClarification").asBoolean(false));
        putNullable(actual, "clarificationCode", nullableText(llmCase, "clarificationCode"));
        actual.set("fieldsUsed", llmCase.path("fieldsUsed").deepCopy());
        actual.put("confidence", llmCase.path("confidence").asDouble(0.0d));

        ObjectNode deterministicNode = node.putObject("deterministic");
        deterministicNode.put("valid", deterministic.valid());
        deterministicNode.put("operationKind", deterministic.operationKind());
        deterministicNode.put("artifactKind", deterministic.artifactKind());
        deterministicNode.put("changeKind", deterministic.changeKind());
        deterministicNode.put("gateStatus", deterministic.gate().status());
        deterministicNode.putPOJO("gateMessages", deterministic.gate().messages());
        deterministicNode.putPOJO("failureCodes", deterministic.failureCodes());
        if (deterministic.selectedCandidate() != null) {
            deterministicNode.put("resourcePath", deterministic.selectedCandidate().resourcePath());
            deterministicNode.put("operation", deterministic.selectedCandidate().operation());
        } else {
            deterministicNode.putNull("resourcePath");
        }

        ArrayNode divergences = node.putArray("divergences");
        addDivergences(matrixCase, llmCase, divergences);
        addCapabilityDivergences(llmCase, capabilities, divergences);
        return node;
    }

    private void addDivergences(MatrixCase expected, JsonNode actual, ArrayNode divergences) {
        compare("operationKind", expected.operationKind(), text(actual, "operationKind"), divergences);
        compare("artifactKind", expected.artifactKind(), text(actual, "artifactKind"), divergences);
        compareChangeKind(expected.changeKind(), text(actual, "changeKind"), divergences);
        compareNullable("resourcePath", expected.resourcePath(), nullableText(actual, "resourcePath"), divergences);
        if (expected.requiresClarification() != actual.path("requiresClarification").asBoolean(false)) {
            divergences.add("requiresClarification expected " + expected.requiresClarification()
                    + " but got " + actual.path("requiresClarification").asBoolean(false));
        }
        compareNullable("clarificationCode", expected.clarificationCode(), nullableText(actual, "clarificationCode"), divergences);
    }

    private void addCapabilityDivergences(JsonNode actual, JsonNode capabilities, ArrayNode divergences) {
        String changeKind = text(actual, "changeKind");
        if (changeKind.isBlank() || "unknown".equals(changeKind) || changeKind.startsWith("create_")
                || changeKind.startsWith("recommend_") || "create_artifact".equals(changeKind)) {
            return;
        }
        for (String part : splitChangeKinds(changeKind)) {
            if (!capabilitySupports(part, capabilities)) {
                divergences.add("changeKind not declared in component-capabilities: " + part);
            }
        }
    }

    private boolean capabilitySupports(String changeKind, JsonNode capabilities) {
        for (JsonNode catalog : capabilities.path("catalogs")) {
            for (JsonNode capability : catalog.path("capabilities")) {
                if (changeKind.equals(text(capability, "changeKind"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void compare(String field, String expected, String actual, ArrayNode divergences) {
        if (!expected.equals(actual)) {
            divergences.add(field + " expected " + expected + " but got " + actual);
        }
    }

    private void compareNullable(String field, String expected, String actual, ArrayNode divergences) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            divergences.add(field + " expected " + value(expected) + " but got " + value(actual));
        }
    }

    private void compareChangeKind(String expected, String actual, ArrayNode divergences) {
        Set<String> expectedKinds = splitChangeKinds(expected);
        Set<String> actualKinds = splitChangeKinds(actual);
        if (!expectedKinds.equals(actualKinds)) {
            divergences.add("changeKind expected " + String.join("|", expectedKinds)
                    + " but got " + String.join("|", actualKinds));
        }
    }

    private Set<String> splitChangeKinds(String changeKind) {
        if (changeKind == null || changeKind.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.asList(changeKind.split("\\|")));
    }

    private JsonNode findLlmCase(JsonNode cases, String id) {
        for (JsonNode candidate : cases) {
            if (id.equals(text(candidate, "id"))) {
                return candidate;
            }
        }
        ObjectNode missing = objectMapper.createObjectNode();
        missing.put("id", id);
        missing.put("operationKind", "unknown");
        missing.put("artifactKind", "unknown");
        missing.put("changeKind", "unknown");
        missing.putNull("resourcePath");
        missing.put("requiresClarification", true);
        missing.put("clarificationCode", "llm-case-missing");
        missing.putArray("fieldsUsed");
        missing.put("confidence", 0.0d);
        return missing;
    }

    private void assertLlmResultShape(JsonNode result, List<MatrixCase> cases) {
        assertThat(text(result, "version")).isEqualTo("1.0.0");
        assertThat(text(result, "kind")).isEqualTo("praxis.ai-authoring.llm-shadow-intent-matrix-result");
        assertThat(result.path("cases")).hasSize(cases.size());
        Set<String> expectedIds = new LinkedHashSet<>(cases.stream().map(MatrixCase::id).toList());
        Set<String> actualIds = new LinkedHashSet<>();
        for (JsonNode node : result.path("cases")) {
            actualIds.add(text(node, "id"));
            assertThat(text(node, "changeKind")).isNotBlank();
            assertThat(node.path("fieldsUsed").isArray()).isTrue();
        }
        assertThat(actualIds).containsExactlyElementsOf(expectedIds);
    }

    private void writeReport(String providerName, int totalCases, int exactMatches, int divergentCases, ArrayNode reportCases) throws Exception {
        Path reportDir = Path.of("target", "agentic-authoring");
        Files.createDirectories(reportDir);

        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", "1.0.0");
        root.put("kind", "praxis.ai-authoring.llm-shadow-intent-matrix-report");
        root.put("provider", providerName);
        root.put("model", modelFor(providerName));
        root.put("generatedAt", Instant.now().toString());
        root.put("matrixPath", "docs/ai/agentic-authoring/proofs/intent-accuracy-matrix.v0.json");
        root.put("componentCapabilitiesPath", "GET /api/praxis/config/ai/authoring/component-capabilities");
        ObjectNode summary = root.putObject("summary");
        summary.put("totalCases", totalCases);
        summary.put("exactMatches", exactMatches);
        summary.put("divergentCases", divergentCases);
        summary.put("accuracy", totalCases == 0 ? 0.0d : ((double) exactMatches) / totalCases);
        root.set("cases", reportCases);

        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(reportDir.resolve("llm-shadow-intent-matrix-result." + providerName + ".json").toFile(), root);
    }

    private SpringAiOpenAiService createOpenAiProvider() {
        String apiKey = requireEnv("PRAXIS_AI_OPENAI_API_KEY");
        @SuppressWarnings("unchecked")
        ObjectProvider<OpenAiChatModel> chatProvider = mock(ObjectProvider.class);
        SpringAiOpenAiService service = new SpringAiOpenAiService(chatProvider, objectMapper);
        ReflectionTestUtils.setField(service, "apiKey", apiKey);
        ReflectionTestUtils.setField(service, "baseUrl", envOrDefault("PRAXIS_AI_OPENAI_BASE_URL", "https://api.openai.com"));
        ReflectionTestUtils.setField(service, "model", envOrDefault("PRAXIS_AI_OPENAI_MODEL", "gpt-4o-mini"));
        ReflectionTestUtils.setField(service, "temperature", 0.0d);
        ReflectionTestUtils.setField(service, "maxTokens", intEnv("PRAXIS_AI_SHADOW_MATRIX_MAX_TOKENS", 8192));
        ReflectionTestUtils.setField(service, "timeoutSeconds", intEnv("PRAXIS_AI_TIMEOUT_SECONDS", 45));
        return service;
    }

    @SuppressWarnings("unchecked")
    private SpringAiGeminiService createGeminiProvider() {
        String apiKey = requireEnv("PRAXIS_AI_GEMINI_API_KEY");
        ObjectProvider<GoogleGenAiChatModel> chatProvider = mock(ObjectProvider.class);
        SpringAiGeminiService service = new SpringAiGeminiService(chatProvider, objectMapper);
        ReflectionTestUtils.setField(service, "apiKey", apiKey);
        ReflectionTestUtils.setField(service, "model", envOrDefault("PRAXIS_AI_GEMINI_MODEL", "gemini-2.5-flash"));
        ReflectionTestUtils.setField(service, "fallbackModels", envOrDefault("PRAXIS_AI_GEMINI_FALLBACK_MODELS", "gemini-2.5-flash,gemini-2.0-flash"));
        ReflectionTestUtils.setField(service, "previewEnabled", Boolean.parseBoolean(envOrDefault("PRAXIS_AI_GEMINI_PREVIEW_ENABLED", "false")));
        ReflectionTestUtils.setField(service, "preferGenaiApi", Boolean.parseBoolean(envOrDefault("PRAXIS_AI_GEMINI_PREFER_GENAI_API", "true")));
        ReflectionTestUtils.setField(service, "retryMaxAttempts", intEnv("PRAXIS_AI_GEMINI_RETRY_MAX_ATTEMPTS", 2));
        ReflectionTestUtils.setField(service, "retryBaseDelayMs", (long) intEnv("PRAXIS_AI_GEMINI_RETRY_BASE_DELAY_MS", 300));
        ReflectionTestUtils.setField(service, "retryJitterPercent", intEnv("PRAXIS_AI_GEMINI_RETRY_JITTER_PERCENT", 30));
        ReflectionTestUtils.setField(service, "breakerThreshold", intEnv("PRAXIS_AI_GEMINI_BREAKER_THRESHOLD", 3));
        ReflectionTestUtils.setField(service, "breakerWindowMs", 30000L);
        ReflectionTestUtils.setField(service, "breakerOpenMs", 30000L);
        ReflectionTestUtils.setField(service, "temperature", 0.0d);
        ReflectionTestUtils.setField(service, "maxTokens", intEnv("PRAXIS_AI_SHADOW_MATRIX_MAX_TOKENS", 8192));
        ReflectionTestUtils.setField(service, "timeoutSeconds", intEnv("PRAXIS_AI_TIMEOUT_SECONDS", 45));
        return service;
    }

    private AiProvider createProvider(String providerName) {
        if ("gemini".equals(providerName)) {
            return createGeminiProvider();
        }
        if ("openai".equals(providerName)) {
            return createOpenAiProvider();
        }
        throw new IllegalStateException("Unsupported shadow provider: " + providerName);
    }

    private AiCallConfig callConfig(String providerName) {
        return AiCallConfig.builder()
                .provider(providerName)
                .model(modelFor(providerName))
                .temperature(0.0d)
                .maxTokens(intEnv("PRAXIS_AI_SHADOW_MATRIX_MAX_TOKENS", 8192))
                .build();
    }

    private String modelFor(String providerName) {
        if ("gemini".equals(providerName)) {
            return envOrDefault("PRAXIS_AI_GEMINI_MODEL", "gemini-2.5-flash");
        }
        return envOrDefault("PRAXIS_AI_OPENAI_MODEL", "gpt-4o-mini");
    }

    private ObjectNode payrollTablePage() {
        ObjectNode page = objectMapper.createObjectNode();
        ArrayNode widgets = page.putArray("widgets");
        ObjectNode widget = widgets.addObject();
        widget.put("key", "payroll-table");
        ObjectNode definition = widget.putObject("definition");
        definition.put("id", "praxis-table");
        ObjectNode inputs = definition.putObject("inputs");
        inputs.put("resourcePath", "/api/human-resources/folhas-pagamento");
        inputs.put("tableId", "payroll-table");
        inputs.put("title", "Folhas de pagamento");
        ArrayNode columns = inputs.putObject("config").putArray("columns");
        columns.addObject().put("field", "id").put("header", "ID").put("type", "number");
        columns.addObject().put("field", "salarioLiquido").put("header", "Salario liquido").put("type", "number");
        columns.addObject().put("field", "salarioBruto").put("header", "Salario bruto").put("type", "number");
        columns.addObject().put("field", "totalDescontos").put("header", "Total descontos").put("type", "number");
        page.putObject("composition").putArray("links");
        return page;
    }

    private ObjectNode payrollChartPage() {
        ObjectNode page = objectMapper.createObjectNode();
        ArrayNode widgets = page.putArray("widgets");
        ObjectNode widget = widgets.addObject();
        widget.put("key", "payroll-chart");
        ObjectNode definition = widget.putObject("definition");
        definition.put("id", "praxis-chart");
        ObjectNode config = definition.putObject("inputs").putObject("config");
        config.put("type", "bar");
        ObjectNode axes = config.putObject("axes");
        axes.putObject("x").put("field", "departamento");
        axes.putObject("y").put("field", "salarioLiquido");
        ObjectNode series = config.putArray("series").addObject();
        series.put("id", "salario-liquido");
        series.put("categoryField", "departamento");
        series.putObject("metric").put("field", "salarioLiquido").put("aggregation", "sum");
        ObjectNode dataSource = config.putObject("dataSource");
        dataSource.put("kind", "remote");
        dataSource.put("resourcePath", "/api/human-resources/vw-analytics-folha-pagamento");
        ObjectNode query = dataSource.putObject("query");
        query.put("sourceKind", "praxis.stats");
        query.put("statsOperation", "group-by");
        query.putArray("dimensions").add("departamento");
        query.putArray("metrics").addObject()
                .put("field", "salarioLiquido")
                .put("aggregation", "sum")
                .put("alias", "salarioLiquido");
        page.putObject("composition").putArray("links");
        return page;
    }

    private ObjectNode employeeFormPage() {
        ObjectNode page = objectMapper.createObjectNode();
        ArrayNode widgets = page.putArray("widgets");
        ObjectNode widget = widgets.addObject();
        widget.put("key", "funcionarios-form");
        ObjectNode definition = widget.putObject("definition");
        definition.put("id", "praxis-dynamic-form");
        ObjectNode inputs = definition.putObject("inputs");
        inputs.put("schemaUrl", "/schemas/filtered?path=/api/human-resources/funcionarios&operation=post&schemaType=request");
        inputs.put("submitUrl", "/api/human-resources/funcionarios");
        inputs.put("submitMethod", "post");
        ObjectNode config = inputs.putObject("config");
        ArrayNode fieldMetadata = config.putArray("fieldMetadata");
        fieldMetadata.addObject().put("name", "nome").put("label", "Nome").put("schemaPointer", "#/properties/nome");
        fieldMetadata.addObject().put("name", "nomeCompleto").put("label", "Nome completo").put("schemaPointer", "#/properties/nomeCompleto");
        fieldMetadata.addObject()
                .put("name", "observacaoInterna")
                .put("label", "Observacao interna")
                .put("source", "local")
                .put("transient", true)
                .put("submitPolicy", "omit");
        return page;
    }

    private Path contractsDir() {
        Path fromModuleDir = Path.of("..", "docs", "ai", "agentic-authoring", "contracts");
        if (Files.exists(fromModuleDir)) {
            return fromModuleDir;
        }
        return Path.of("docs", "ai", "agentic-authoring", "contracts");
    }

    private Path proofsDir() {
        Path fromModuleDir = Path.of("..", "docs", "ai", "agentic-authoring", "proofs");
        if (Files.exists(fromModuleDir)) {
            return fromModuleDir;
        }
        return Path.of("docs", "ai", "agentic-authoring", "proofs");
    }

    private String requireEnv(String name) {
        String value = env(name);
        if (value == null || value.isBlank() || value.startsWith("PASTE_")) {
            throw new IllegalStateException(name + " must be configured for LLM shadow tests.");
        }
        return value;
    }

    private String envOrDefault(String name, String fallback) {
        String value = env(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private int intEnv(String name, int fallback) {
        String value = env(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Integer.parseInt(value.trim());
    }

    private String env(String name) {
        return System.getenv(name);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private String nullableText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText("");
        return text.isBlank() ? null : text;
    }

    private void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private String value(String value) {
        return value == null ? "null" : value;
    }

    private record MatrixCase(
            String id,
            String userPrompt,
            String operationKind,
            String artifactKind,
            String changeKind,
            String resourcePath,
            boolean requiresClarification,
            String clarificationCode,
            String status
    ) {
    }
}
