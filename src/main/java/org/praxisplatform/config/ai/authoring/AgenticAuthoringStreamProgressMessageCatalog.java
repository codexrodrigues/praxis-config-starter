package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.praxisplatform.config.dto.AiTurnEventEnvelope;

/**
 * Catalog of phase-aware progress, heartbeat, and duplicate-diagnostics messages for
 * {@link AgenticAuthoringTurnStreamService}.
 */
public final class AgenticAuthoringStreamProgressMessageCatalog {

    public static final AgenticAuthoringStreamProgressMessageCatalog INSTANCE =
            new AgenticAuthoringStreamProgressMessageCatalog();

    public String heartbeatPhase(AiTurnEventEnvelope tail) {
        JsonNode payload = tail == null ? null : tail.getPayload();
        String phase = payload == null ? "" : payload.path("phase").asText("");
        if (phase != null && !phase.isBlank()) {
            return phase;
        }
        String type = tail == null ? "" : nonBlank(tail.getType(), "");
        return switch (type) {
            case "intent.resolved" -> "intent.resolve.grounding";
            default -> "agentic-authoring";
        };
    }

    public void putTechnicalDuplicateDiagnostics(
            Map<String, Object> payload,
            String eventType,
            UUID streamId,
            String phase,
            AiTurnEventEnvelope tail) {
        Map<String, Object> diagnostics = technicalDuplicateStreamEventDiagnostics(eventType, streamId, phase, tail);
        if (!diagnostics.isEmpty()) {
            payload.put("streamEventDiagnostics", diagnostics);
        }
    }

    public Map<String, Object> technicalDuplicateStreamEventDiagnostics(
            String eventType,
            UUID streamId,
            String phase,
            AiTurnEventEnvelope tail) {
        JsonNode tailDiagnostics = tail == null || tail.getPayload() == null
                ? null
                : tail.getPayload().path("streamEventDiagnostics");
        if (tailDiagnostics == null || !tailDiagnostics.isObject()) {
            return Map.of();
        }
        String dedupeKey = tailDiagnostics.path("dedupeKey").asText("");
        if (dedupeKey == null || dedupeKey.isBlank()) {
            return Map.of();
        }
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("schemaVersion", "praxis-authoring-stream-event-diagnostics.v1");
        diagnostics.put("dedupeKey", dedupeKey);
        diagnostics.put("eventUniquenessKey", safeDiagnosticText(eventType) + ":"
                + (streamId == null ? "" : streamId) + ":"
                + safeDiagnosticText(phase) + ":"
                + Instant.now().toEpochMilli());
        diagnostics.put("technicalDuplicate", true);
        diagnostics.put("technicalDuplicateOf", dedupeKey);
        diagnostics.put("replaySafe", true);
        diagnostics.put("duplicatesDoNotIndicateExecution", true);
        return diagnostics;
    }

    public String heartbeatSummary(AiTurnEventEnvelope tail) {
        JsonNode payload = tail == null ? null : tail.getPayload();
        String explicitMessage = payload == null ? "" : payload.path("message").asText("");
        if (explicitMessage != null && !explicitMessage.isBlank()) {
            return explicitMessage;
        }
        String label = payload == null ? "" : payload.path("label").asText("");
        if (label != null && !label.isBlank()) {
            return label;
        }
        String phase = heartbeatPhase(tail);
        return switch (phase) {
            case "consultative.intent" ->
                    "Estou separando pergunta consultiva de pedido de criacao para seguir pelo caminho certo.";
            case "consultative.answer" ->
                    "Encontrei evidencias governadas e estou transformando isso em uma resposta clara.";
            case "intent.resolve" ->
                    "Estou organizando o pedido, a pagina atual e as restricoes governadas.";
            case "intent.resolve.evidence" ->
                    "Estou avaliando os candidatos governados recuperados antes de materializar.";
            case "intent.resolve.llm" ->
                    "A LLM esta revisando a intencao com a evidencia governada recuperada.";
            case "intent.resolve.grounding" ->
                    "Estou validando a intencao com as evidencias disponiveis.";
            case "tool.plan" ->
                    "A LLM esta planejando quais ferramentas de leitura consultar.";
            case "tool.plan.skipped" ->
                    "O planejamento de ferramentas foi ignorado com diagnostico registrado.";
            case "resource.discovery", "tool.start", "tool.result", "tool.error" ->
                    "Estou consultando recursos, schemas e capacidades do backend.";
            case "authoringEvidence.retrieve" ->
                    "Estou buscando evidencias de componentes para planejar a pre-visualizacao.";
            case "authoringEvidence.result" ->
                    "As evidencias de componentes foram recuperadas para a pre-visualizacao.";
            case "authoringEvidence.skipped" ->
                    "Nao ha componente selecionado para buscar evidencia granular de autoria; vou seguir com as evidencias do recurso governado.";
            case "component.capabilities" ->
                    "Estou carregando capacidades governadas dos componentes para materializar a tela corretamente.";
            case "projectKnowledge.retrieve" ->
                    "Estou buscando conhecimento governado do projeto.";
            case "projectKnowledge.result" ->
                    "A busca de conhecimento governado do projeto foi concluida.";
            case "preview.plan" ->
                    "Estou planejando a materializacao governada da tela.";
            case "preview.compile" ->
                    "Estou preparando a pre-visualizacao governada.";
            case "tool.loop" ->
                    "Estou concluindo a validacao governada da pre-visualizacao.";
            default ->
                    "Ainda estou processando sua solicitacao.";
        };
    }

    public String processingProgressMessage(AiTurnEventEnvelope tail, long elapsedSeconds) {
        String phase = heartbeatPhase(tail);
        String elapsed = elapsedSeconds >= 20
                ? " Ja se passaram cerca de " + elapsedSeconds + " segundos; continuo trabalhando nisso."
                : "";
        return switch (phase) {
            case "context.bundle" ->
                    "Recebi seu pedido e estou montando o contexto governado para decidir o proximo passo." + elapsed;
            case "runtime.context.grounding" ->
                    "Estou conferindo o estado atual da pagina para evitar alterar o componente errado." + elapsed;
            case "tool.plan" ->
                    "A LLM esta planejando quais buscas governadas fazem sentido antes de escolher uma fonte." + elapsed;
            case "tool.plan.skipped" ->
                    "O planejamento de ferramentas foi concluido com diagnostico registrado; estou seguindo pelo caminho seguro." + elapsed;
            case "tool.start", "resource.discovery" ->
                    "Estou consultando o catalogo governado e procurando recursos compativeis com sua intencao." + elapsed;
            case "tool.result" ->
                    "Ja recuperei candidatos governados e estou usando essas evidencias para decidir a melhor fonte." + elapsed;
            case "intent.resolve" ->
                    "Estou organizando sua intencao em uma decisao canonica antes de montar a tela." + elapsed;
            case "intent.resolve.evidence" ->
                    "Estou avaliando os candidatos governados recuperados para decidir a fonte correta." + elapsed;
            case "intent.resolve.llm" ->
                    "A LLM esta revisando a intencao com as evidencias governadas; essa etapa pode levar alguns segundos." + elapsed;
            case "intent.resolve.grounding" ->
                    "Estou validando a decisao semantica contra os dados confirmados do backend." + elapsed;
            case "preview.plan" ->
                    "Estou planejando como materializar a decisao em uma pre-visualizacao revisavel." + elapsed;
            case "preview.compile" ->
                    "Estou compilando a pre-visualizacao e conferindo campos, componentes e fonte de dados." + elapsed;
            case "authoringEvidence.skipped" ->
                    "Nao ha componente selecionado para buscar evidencia granular de autoria; estou seguindo com as evidencias do recurso governado." + elapsed;
            case "component.capabilities" ->
                    "Estou carregando capacidades governadas dos componentes para decidir como materializar a tela." + elapsed;
            case "repair.attempt" ->
                    "Encontrei algo para ajustar e estou tentando reparar a pre-visualizacao com contexto governado." + elapsed;
            case "consultative.intent", "consultative.answer", "consultative.post-intent.probe" ->
                    "Estou separando orientacao de criacao de tela para responder sem perder o contexto governado." + elapsed;
            default ->
                    "Ainda estou processando sua solicitacao com as evidencias disponiveis." + elapsed;
        };
    }

    private String safeDiagnosticText(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9_.:-]", "_");
    }

    private String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
