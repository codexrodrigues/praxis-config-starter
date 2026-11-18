package org.praxisplatform.config.ai.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.praxisplatform.config.ai.agent.model.AgentToolDeclaration;
import org.praxisplatform.config.ai.agent.runtime.AgentTurnArtifactStore;
import org.praxisplatform.config.ai.agent.runtime.Mod11IdentifierHintExtractor;
import org.praxisplatform.config.ai.agent.runtime.Mod11IdentifierHintExtractor.TaxIdMatch;
import org.praxisplatform.config.ai.agent.runtime.OperationalAssistantBlockComposer;
import org.praxisplatform.config.ai.agent.runtime.TurnScopedPiiVault;
import org.praxisplatform.config.ai.agent.spi.AgentEntityMatch;
import org.praxisplatform.config.ai.agent.spi.AgentEntitySearchRequest;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentResourceExecutionBridge;
import org.springframework.util.StringUtils;

/**
 * Ferramenta primaria para resolucao polimorfica de entidades antes de assumir qual dominio ou
 * tela o usuario deseja consultar.
 */
public class SearchAndResolveEntitiesTool implements OperationalAgentTool {

    public static final String TOOL_NAME = "search_and_resolve_entities";

    private final AgentResourceExecutionBridge bridge;

    public SearchAndResolveEntitiesTool(AgentResourceExecutionBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge is required");
    }

    @Override
    public String name() {
        return TOOL_NAME;
    }

    @Override
    public AgentToolDeclaration declaration() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of(
                "type", "string",
                "description", "Texto, nome, codigo, matricula ou placeholder [TAX_ID_n] informado pelo usuario."));
        properties.put("entityKeys", Map.of(
                "type", "array",
                "description", "Opcional: lista de entityKeys/resourceKeys candidatos para restringir a busca.",
                "items", Map.of("type", "string")));
        properties.put("maxPerEntity", Map.of(
                "type", "integer",
                "description", "Opcional: maximo de candidatos retornados por entidade (padrao 5)."));
        return new AgentToolDeclaration(
                TOOL_NAME,
                "Busca e resolve polimorficamente candidatos em todas as entidades cadastradas (por CPF/CNPJ valido Mod-11, ID/matricula, nome ou descricao) antes de abrir um dossie 360 ou listar homonimos.",
                Map.of(
                        "type", "object",
                        "properties", properties),
                List.of("query"));
    }

    @Override
    public Map<String, Object> execute(
            AgentExecutionContext context,
            TurnScopedPiiVault piiVault,
            AgentTurnArtifactStore artifacts,
            Map<String, Object> rawArguments) {
        Map<String, Object> args = rawArguments == null ? Map.of() : rawArguments;
        String rawQuery = asString(args.get("query"));
        if (!StringUtils.hasText(rawQuery)) {
            return Map.of("matchCount", 0, "status", "EMPTY_QUERY", "candidates", List.of());
        }
        Optional<String> vaultedDigits = piiVault != null ? piiVault.resolveDigits(rawQuery) : Optional.empty();
        String rehydratedQuery = piiVault != null ? piiVault.rehydrateText(rawQuery) : rawQuery;

        String resolvedTaxId = vaultedDigits.orElse(null);
        boolean validTaxId = StringUtils.hasText(resolvedTaxId);
        if (!validTaxId) {
            List<TaxIdMatch> directTax = Mod11IdentifierHintExtractor.extractValidTaxIds(rehydratedQuery);
            if (!directTax.isEmpty()) {
                resolvedTaxId = directTax.get(0).digits();
                validTaxId = true;
            }
        }

        List<String> candidateEntityKeys = asStringList(args.get("entityKeys"));
        int maxPerEntity = asInt(args.get("maxPerEntity"), 5);

        List<AgentEntityMatch> matches = bridge.searchEntities(
                context,
                new AgentEntitySearchRequest(
                        rehydratedQuery,
                        resolvedTaxId,
                        validTaxId,
                        candidateEntityKeys,
                        maxPerEntity));
        artifacts.recordSearchMatches(matches);

        String status = switch (matches.size()) {
            case 0 -> "NO_MATCH";
            case 1 -> "SINGLE_MATCH";
            default -> "MULTIPLE_CANDIDATES";
        };

        List<Map<String, Object>> modelCandidates = new ArrayList<>(matches.size());
        for (AgentEntityMatch match : matches) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("entityKey", match.entityKey());
            entry.put("resourceKey", match.resourceKey());
            entry.put("resourcePath", match.resourcePath());
            entry.put("entityTitle", match.entityTitle());
            entry.put("entityId", match.entityId());
            entry.put("title", match.title());
            entry.put("subtitle", match.subtitle());
            entry.put("matchedBy", match.matchedBy());
            entry.put("confidence", match.confidence());
            entry.put("fromActiveScreenScope", match.fromActiveScreenScope());
            entry.put("recordSummary", match.modelSafeSummary());
            modelCandidates.add(entry);
        }

        Map<String, Object> autoLoadedDossier = null;
        if (matches.size() == 1) {
            AgentEntityMatch only = matches.get(0);
            try {
                Optional<org.praxisplatform.config.ai.agent.spi.AgentEntityDossier> loaded =
                        bridge.loadEntityDossier(context, only.entityKey(), only.entityId(), List.of());
                if (loaded.isPresent()) {
                    org.praxisplatform.config.ai.agent.spi.AgentEntityDossier dossier = loaded.get();
                    if (artifacts != null) {
                        artifacts.recordDossier(dossier);
                    }
                    autoLoadedDossier = GetEntity360DossierTool.toModelSafeMap(dossier);
                }
            } catch (RuntimeException ignored) {
                // Defensive: preserve search match even if dossier enrichment cannot be loaded.
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("matchCount", matches.size());
        result.put("validTaxIdMod11", validTaxId);
        result.put("candidates", modelCandidates);
        if (autoLoadedDossier != null) {
            result.put("autoLoadedDossier", autoLoadedDossier);
        }
        if (matches.isEmpty()) {
            result.put("hint", "Nenhum registro correspondeu na busca polimorfica global; responda de forma objetiva informando que nao houve correspondencia, sem repetir varias chamadas query_resource_records vazias.");
        }
        Map<String, Object> scrubbed = piiVault != null ? piiVault.scrubOutboundForModel(result) : result;
        return OperationalAssistantBlockComposer.maskMod11InStructure(scrubbed);
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString().trim();
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number num) {
            return num.intValue();
        }
        if (value instanceof String str && StringUtils.hasText(str)) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }
}
