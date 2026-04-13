package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reference plan provider used by the Quickstart-hosted authoring demo.
 *
 * <p>This provider is intentionally narrow: it recognizes the Human Resources master-detail
 * benchmark that the official Quickstart exposes today and returns a {@code UiCompositionPlan}
 * instead of asking the minimal-form compiler to invent multi-widget page JSON.</p>
 */
public class AgenticAuthoringReferenceUiCompositionPlanProvider implements AgenticAuthoringUiCompositionPlanProvider {

    private static final String DEPARTMENTS = "/api/human-resources/departamentos";
    private static final String EMPLOYEES = "/api/human-resources/funcionarios";
    private static final String PAYROLL_ANALYTICS = "/api/human-resources/vw-analytics-folha-pagamento";

    private final ObjectMapper objectMapper;

    public AgenticAuthoringReferenceUiCompositionPlanProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<AgenticAuthoringUiCompositionPlanResult> plan(AgenticAuthoringPlanRequest request) {
        if (!supports(request)) {
            return Optional.empty();
        }
        return Optional.of(new AgenticAuthoringUiCompositionPlanResult(
                true,
                List.of(),
                List.of("ui-composition-plan-provider:quickstart-human-resources"),
                masterDetailPlan(),
                emptyCompiledFormPatch()));
    }

    private boolean supports(AgenticAuthoringPlanRequest request) {
        if (request == null || request.userPrompt() == null) {
            return false;
        }
        String prompt = normalize(request.userPrompt());
        boolean asksForPageComposition = containsAny(prompt,
                "master detail",
                "master-detail",
                "mestre detalhe",
                "mestre-detalhe",
                "dashboard",
                "painel");
        boolean referencesDepartments = containsAny(prompt, "departamento", "departamentos", "department", "departments");
        boolean referencesEmployees = containsAny(prompt, "funcionario", "funcionarios", "colaborador", "colaboradores", "employee", "employees");
        return asksForPageComposition && referencesDepartments && referencesEmployees;
    }

    private ObjectNode masterDetailPlan() {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("version", "1.0");
        plan.put("kind", "praxis.ui-composition-plan");
        plan.put("layoutPreset", "master-detail-dashboard");

        ObjectNode values = plan.putObject("state").putObject("values");
        values.putNull("selectedDepartmentId");
        values.putNull("selectedDepartmentName");

        ArrayNode widgets = plan.putArray("widgets");
        addDepartmentMaster(widgets);
        addEmployeesDetail(widgets);
        addPayrollChart(widgets);
        addDepartmentSummary(widgets);

        ArrayNode bindings = plan.putArray("bindings");
        addPickStateBinding(bindings, "department-master.itemSelect->state.selectedDepartmentId",
                "selectedDepartmentId", "pick-department-id", "payload.item.id");
        addPickStateBinding(bindings, "department-master.itemSelect->state.selectedDepartmentName",
                "selectedDepartmentName", "pick-department-name", "payload.item.nome");
        addQueryContextBinding(bindings, "state.selectedDepartmentId->department-employees.queryContext",
                "department-employees", "department-filter", "departamentoId", "table-filter");
        addQueryContextBinding(bindings, "state.selectedDepartmentId->department-payroll.queryContext",
                "department-payroll", "department-payroll-filter", "departamentoId", "chart-filter");
        addSummaryDocumentBinding(bindings);
        return plan;
    }

    private void addDepartmentMaster(ArrayNode widgets) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", "department-master");
        widget.put("componentId", "praxis-list");
        widget.put("role", "master");
        widget.putObject("inputs").putObject("config").putObject("dataSource").put("resourcePath", DEPARTMENTS);
    }

    private void addEmployeesDetail(ArrayNode widgets) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", "department-employees");
        widget.put("componentId", "praxis-table");
        widget.put("role", "detail");
        ObjectNode inputs = widget.putObject("inputs");
        inputs.put("resourcePath", EMPLOYEES);
        inputs.put("tableId", "department-employees");
    }

    private void addPayrollChart(ArrayNode widgets) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", "department-payroll");
        widget.put("componentId", "praxis-chart");
        widget.put("role", "detail");
        ObjectNode config = widget.putObject("inputs").putObject("config");
        config.put("chartId", "department-payroll");
        config.put("resourcePath", PAYROLL_ANALYTICS);
    }

    private void addDepartmentSummary(ArrayNode widgets) {
        ObjectNode widget = widgets.addObject();
        widget.put("key", "department-summary");
        widget.put("componentId", "praxis-rich-content");
        widget.put("role", "detail");
        ObjectNode document = widget.putObject("inputs").putObject("document");
        document.put("kind", "praxis.rich-content.document");
        document.put("version", "1.0.0");
        ObjectNode text = document.putArray("nodes").addObject();
        text.put("type", "text");
        text.put("text", "Selecione um departamento para revisar funcionarios, folha e observacoes.");
    }

    private void addPickStateBinding(
            ArrayNode bindings,
            String id,
            String statePath,
            String transformId,
            String payloadPath) {
        ObjectNode binding = baseBinding(bindings, id, "state-write");
        ObjectNode from = binding.putObject("from");
        from.put("kind", "component-port");
        from.put("widget", "department-master");
        from.put("port", "itemSelect");
        from.put("direction", "output");
        ObjectNode to = binding.putObject("to");
        to.put("kind", "state");
        to.put("path", statePath);
        ObjectNode transform = binding.putObject("transform");
        transform.put("kind", "pick-path");
        transform.put("id", transformId);
        transform.put("path", payloadPath);
        addMetadata(binding, "state");
    }

    private void addQueryContextBinding(
            ArrayNode bindings,
            String id,
            String targetWidget,
            String transformId,
            String field,
            String tag) {
        ObjectNode binding = baseBinding(bindings, id, "state-read");
        ObjectNode from = binding.putObject("from");
        from.put("kind", "state");
        from.put("path", "selectedDepartmentId");
        ObjectNode to = binding.putObject("to");
        to.put("kind", "component-port");
        to.put("widget", targetWidget);
        to.put("port", "queryContext");
        to.put("direction", "input");
        ObjectNode condition = binding.putObject("condition");
        ArrayNode notEquals = condition.putArray("!=");
        notEquals.addObject().put("var", "state.selectedDepartmentId");
        notEquals.addNull();
        ObjectNode transform = binding.putObject("transform");
        transform.put("kind", "query-context");
        transform.put("id", transformId);
        transform.put("field", field);
        ObjectNode policy = binding.putObject("policy");
        policy.put("distinct", true);
        policy.put("missingValuePolicy", "skip");
        addMetadata(binding, tag);
    }

    private void addSummaryDocumentBinding(ArrayNode bindings) {
        ObjectNode binding = baseBinding(bindings, "state.selectedDepartmentName->department-summary.document", "state-read");
        ObjectNode from = binding.putObject("from");
        from.put("kind", "state");
        from.put("path", "selectedDepartmentName");
        ObjectNode to = binding.putObject("to");
        to.put("kind", "component-port");
        to.put("widget", "department-summary");
        to.put("port", "document");
        to.put("direction", "input");
        binding.putObject("condition").putArray("!!").addObject().put("var", "state.selectedDepartmentName");
        ObjectNode transform = binding.putObject("transform");
        transform.put("kind", "template");
        transform.put("id", "department-summary-document");
        ObjectNode template = transform.putObject("template");
        template.put("kind", "praxis.rich-content.document");
        template.put("version", "1.0.0");
        ObjectNode node = template.putArray("nodes").addObject();
        node.put("type", "text");
        ArrayNode concat = node.putObject("text").putArray("concat");
        concat.add("Departamento selecionado: ");
        concat.addObject().put("var", "value");
        addMetadata(binding, "rich-content");
    }

    private ObjectNode baseBinding(ArrayNode bindings, String id, String intent) {
        ObjectNode binding = bindings.addObject();
        binding.put("id", id);
        binding.put("intent", intent);
        return binding;
    }

    private void addMetadata(ObjectNode binding, String tag) {
        ObjectNode metadata = binding.putObject("metadata");
        metadata.put("source", "ui-composition-plan");
        ArrayNode tags = metadata.putArray("tags");
        tags.add("master-detail");
        tags.add(tag);
    }

    private ObjectNode emptyCompiledFormPatch() {
        ObjectNode patch = objectMapper.createObjectNode();
        patch.put("version", "1.0.0");
        patch.put("profileId", "ui-composition-plan");
        patch.put("targetComponentId", "praxis-dynamic-page-builder");
        patch.putObject("patch");
        patch.putObject("compatibility")
                .put("aiHttpContract", "v1.1")
                .put("publicResponseKind", "ui-composition-plan")
                .put("requiresV12", false);
        patch.put("builderVersion", "ui-composition-plan-provider@0.1.0-draft");
        patch.putArray("warnings").add("compiled-form-patch-materialized-by-page-builder");
        return patch;
    }

    private boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.toLowerCase(Locale.ROOT).trim();
    }
}
