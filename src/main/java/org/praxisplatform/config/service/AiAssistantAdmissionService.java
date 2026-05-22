package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.dto.AiOrchestratorResponse;
import org.praxisplatform.config.dto.AiCapability;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Canonical turn admission policy for public AI authoring surfaces.
 *
 * <p>This service does not route product intent. It only enforces platform safety and the
 * declared assistant scope before a provider call is allowed.</p>
 */
@Service
public class AiAssistantAdmissionService {

    public static final String CODE_POLICY_REJECTED = "AI_TURN_POLICY_REJECTED";
    public static final String CODE_OUT_OF_SCOPE = "AI_TURN_OUT_OF_SCOPE";

    private static final String SECURITY_MESSAGE =
            "Nao posso ajudar com esse pedido. Posso orientar ou propor ajustes apenas dentro do contrato publico do componente Praxis atual.";
    private static final String OUT_OF_SCOPE_MESSAGE =
            "Este assistente publico responde apenas sobre configuracao, capacidades e exemplos do componente Praxis atual.";

    @Value("${praxis.ai.assistant.admission.enabled:true}")
    private boolean enabled;

    public AiOrchestratorResponse evaluate(
            AiOrchestratorRequest request,
            JsonNode authoringContract,
            JsonNode authoringManifest,
            List<AiCapability> configCapabilities,
            List<AiCapability> componentCapabilities,
            JsonNode componentContext) {
        if (!enabled || request == null) {
            return null;
        }
        String prompt = normalize(resolvePrompt(request));
        if (prompt.isBlank()) {
            return null;
        }
        if (isUniversalSecurityRejection(prompt)) {
            return policyResponse(CODE_POLICY_REJECTED, SECURITY_MESSAGE, request);
        }
        if (isOutOfScopePublicRequest(prompt, request, authoringContract, authoringManifest,
                configCapabilities, componentCapabilities, componentContext)) {
            return policyResponse(CODE_OUT_OF_SCOPE, OUT_OF_SCOPE_MESSAGE, request);
        }
        return null;
    }

    private boolean isUniversalSecurityRejection(String prompt) {
        if (containsAny(prompt,
                "system prompt",
                "prompt do sistema",
                "instrucoes do sistema",
                "instrucoes internas",
                "ignore as instrucoes",
                "ignorar instrucoes",
                "desconsidere as instrucoes",
                "developer message",
                "mensagem de desenvolvedor",
                "jailbreak",
                "bypass",
                "oculte os logs",
                "esconda os logs",
                "sem registrar log",
                "nao registre log")) {
            return true;
        }
        if (containsAny(prompt,
                "api key",
                "apikey",
                "openai_api_key",
                "gemini_api_key",
                "token secret",
                "auth token secret",
                "client secret",
                "senha",
                "password",
                "credential",
                "credencial",
                "env var",
                "variavel de ambiente",
                ".env")) {
            return true;
        }
        boolean destructiveAction = containsAny(prompt,
                "delete em massa",
                "delete all",
                "apague todos",
                "apagar todos",
                "excluir todos",
                "remover todos",
                "truncate",
                "drop table",
                "delete from",
                "sem pedir confirmacao",
                "sem confirmacao");
        boolean externalExecution = containsAny(prompt,
                "endpoint",
                "credenciais",
                "autorizacao",
                "authorization",
                "execute agora",
                "faca delete",
                "chame a api",
                "http delete");
        return destructiveAction && externalExecution;
    }

    private boolean isOutOfScopePublicRequest(
            String prompt,
            AiOrchestratorRequest request,
            JsonNode authoringContract,
            JsonNode authoringManifest,
            List<AiCapability> configCapabilities,
            List<AiCapability> componentCapabilities,
            JsonNode componentContext) {
        if (!looksLikeGeneralCreativeOrHomework(prompt)) {
            return false;
        }
        String groundingText = normalize(String.join(" ",
                safe(request.getComponentId()),
                safe(request.getComponentType()),
                safe(request.getResourcePath()),
                safeJson(authoringContract),
                safeJson(authoringManifest),
                safeJson(componentContext),
                capabilityText(configCapabilities),
                capabilityText(componentCapabilities)));
        return !hasAnyTokenOverlap(prompt, groundingText);
    }

    private boolean looksLikeGeneralCreativeOrHomework(String prompt) {
        return containsAny(prompt,
                "escreva um poema",
                "poema longo",
                "conte uma piada",
                "me conte uma piada",
                "escreva uma historia",
                "historia infantil",
                "receita de bolo",
                "plano de viagem",
                "redacao escolar",
                "homework",
                "write a poem",
                "tell me a joke",
                "write a story");
    }

    private boolean hasAnyTokenOverlap(String prompt, String groundingText) {
        if (groundingText == null || groundingText.isBlank()) {
            return false;
        }
        for (String token : significantTokens(prompt)) {
            if (groundingText.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private List<String> significantTokens(String value) {
        String[] tokens = value.replaceAll("[^a-z0-9]+", " ").trim().split("\\s+");
        List<String> result = new ArrayList<>();
        for (String token : tokens) {
            if (token.length() >= 5 && !isStopword(token)) {
                result.add(token);
            }
        }
        return result;
    }

    private boolean isStopword(String token) {
        return List.of(
                "escreva", "longo", "conte", "sobre", "quero", "preciso", "please", "write", "about")
                .contains(token);
    }

    private AiOrchestratorResponse policyResponse(String code, String message, AiOrchestratorRequest request) {
        return AiOrchestratorResponse.builder()
                .type("info")
                .code(code)
                .message(message)
                .explanation(message)
                .warnings(List.of("assistant-admission-policy-applied"))
                .componentId(request.getComponentId())
                .componentType(request.getComponentType())
                .build();
    }

    private String resolvePrompt(AiOrchestratorRequest request) {
        if (request.getUserPrompt() != null && !request.getUserPrompt().isBlank()) {
            return request.getUserPrompt();
        }
        if (request.getMessages() == null || request.getMessages().isEmpty()) {
            return "";
        }
        for (int i = request.getMessages().size() - 1; i >= 0; i--) {
            var message = request.getMessages().get(i);
            if (message != null
                    && "user".equalsIgnoreCase(message.getRole())
                    && message.getContent() != null
                    && !message.getContent().isBlank()) {
                return message.getContent();
            }
        }
        return "";
    }

    private String capabilityText(List<AiCapability> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (AiCapability capability : capabilities) {
            if (capability == null) {
                continue;
            }
            sb.append(' ')
                    .append(safe(capability.getPath()))
                    .append(' ')
                    .append(safe(capability.getCategory()))
                    .append(' ')
                    .append(safe(capability.getDescription()))
                    .append(' ')
                    .append(safe(capability.getSafetyNotes()));
        }
        return sb.toString();
    }

    private boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (token != null && !token.isBlank() && value.contains(normalize(token))) {
                return true;
            }
        }
        return false;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String safeJson(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? "" : node.toString();
    }

    private String normalize(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return normalized.toLowerCase(Locale.ROOT).trim();
    }
}
