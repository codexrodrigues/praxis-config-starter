package org.praxisplatform.config.ai.authoring;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.util.StringUtils;

final class AgenticAuthoringConsultativeGroundingAlignment {

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "agora", "ai", "ainda", "ao", "aos", "as", "assim", "atrasado", "atrasados", "aqui", "com", "como", "consigo",
            "consegue", "conseguimos", "criar", "da", "das", "de", "deve", "do", "dos", "e", "em", "esse",
            "esses", "esta", "estao", "faca", "fazer", "faz", "host", "me", "nao", "nesse",
            "neste", "o", "os", "ou", "para", "por", "pra", "quais", "qual", "quando", "que", "quero",
            "sobre", "tem", "tenho", "um", "uma", "voce",
            "api", "apis", "dado", "dados", "fonte", "fontes", "informacao", "informacoes", "recurso", "recursos",
            "administrativo", "dashboard", "grafico", "graficos", "painel", "paineis", "tela", "telas",
            "consultar", "consulta", "consultas", "existe", "existem", "recomenda", "recomendar",
            "recomendacao", "recomendacoes");

    private AgenticAuthoringConsultativeGroundingAlignment() {
    }

    static List<String> requestedConcepts(String prompt) {
        Set<String> concepts = new LinkedHashSet<>();
        for (String token : normalized(prompt).split("[^a-z0-9]+")) {
            if (token.length() < 3 || STOP_WORDS.contains(token)) {
                continue;
            }
            concepts.add(token);
        }
        return concepts.stream().limit(6).toList();
    }

    static boolean hasRequestedConceptCoverage(
            String prompt,
            List<AgenticAuthoringConsultativeApiCatalogProjection.Resource> resources) {
        List<String> concepts = requestedConcepts(prompt);
        if (concepts.isEmpty()) {
            return true;
        }
        String evidenceText = normalized(resourceEvidenceText(resources));
        if (evidenceText.isBlank()) {
            return false;
        }
        return concepts.stream().anyMatch(concept -> containsConcept(evidenceText, concept));
    }

    static String unsupportedDomainMessage(
            String prompt,
            List<AgenticAuthoringConsultativeApiCatalogProjection.Resource> resources) {
        List<String> concepts = requestedConcepts(prompt);
        if (concepts.isEmpty() || hasRequestedConceptCoverage(prompt, resources)) {
            return "";
        }
        StringBuilder message = new StringBuilder();
        message.append("Nao encontrei dados governados confirmados neste host para ")
                .append(humanJoin(concepts))
                .append(". ");
        List<String> alternatives = resources == null ? List.of() : resources.stream()
                .filter(resource -> resource != null && hasMeaningfulResourceLabel(resource.label()))
                .map(resource -> resource.label().trim())
                .distinct()
                .limit(5)
                .toList();
        if (!alternatives.isEmpty()) {
            message.append("O que encontrei confirmado no catalogo foi ")
                    .append(humanJoin(alternatives))
                    .append(". Posso recomendar telas ou criar uma previa usando essas fontes confirmadas, ")
                    .append("ou o dominio pedido precisa ser publicado no catalogo/RAG deste host antes de eu trata-lo como disponivel.");
        } else {
            message.append("Antes de recomendar telas ou APIs para esse assunto, esse dominio precisa estar publicado no catalogo/RAG deste host.");
        }
        return message.toString();
    }

    private static boolean containsConcept(String evidenceText, String concept) {
        String token = normalized(concept).trim();
        if (token.isBlank()) {
            return false;
        }
        return evidenceText.contains(" " + token + " ")
                || evidenceText.contains(" " + singular(token) + " ")
                || evidenceText.contains(" " + plural(token) + " ");
    }

    private static String resourceEvidenceText(
            List<AgenticAuthoringConsultativeApiCatalogProjection.Resource> resources) {
        StringBuilder text = new StringBuilder();
        if (resources == null) {
            return "";
        }
        for (AgenticAuthoringConsultativeApiCatalogProjection.Resource resource : resources) {
            if (resource == null) {
                continue;
            }
            text.append(' ').append(resource.resourceKey());
            text.append(' ').append(resource.resourcePath());
            text.append(' ').append(resource.label());
            text.append(' ').append(resource.description());
            if (resource.fields() != null) {
                for (AgenticAuthoringConsultativeApiCatalogProjection.Field field : resource.fields()) {
                    if (field == null) {
                        continue;
                    }
                    text.append(' ').append(field.name());
                    text.append(' ').append(field.label());
                    text.append(' ').append(field.description());
                }
            }
            if (resource.actions() != null) {
                for (AgenticAuthoringConsultativeApiCatalogProjection.Action action : resource.actions()) {
                    if (action == null) {
                        continue;
                    }
                    text.append(' ').append(action.name());
                    text.append(' ').append(action.label());
                    text.append(' ').append(action.description());
                }
            }
            if (resource.evidence() != null) {
                resource.evidence().forEach(value -> text.append(' ').append(value));
            }
        }
        return text.toString();
    }

    private static String singular(String token) {
        if (token.length() > 4 && token.endsWith("oes")) {
            return token.substring(0, token.length() - 3) + "ao";
        }
        if (token.length() > 4 && token.endsWith("ais")) {
            return token.substring(0, token.length() - 2) + "l";
        }
        if (token.length() > 3 && token.endsWith("s")) {
            return token.substring(0, token.length() - 1);
        }
        return token;
    }

    private static String plural(String token) {
        return token.endsWith("s") ? token : token + "s";
    }

    private static String humanJoin(List<String> values) {
        List<String> clean = values == null ? List.of() : values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (clean.isEmpty()) {
            return "";
        }
        if (clean.size() == 1) {
            return clean.get(0);
        }
        return String.join(", ", clean.subList(0, clean.size() - 1)) + " e " + clean.get(clean.size() - 1);
    }

    private static boolean hasMeaningfulResourceLabel(String label) {
        String normalized = normalized(label).trim();
        return StringUtils.hasText(label)
                && !Set.of(
                "descricao",
                "description",
                "resumo",
                "summary",
                "contexto",
                "context",
                "api",
                "apis",
                "recurso",
                "resource",
                "dados",
                "data").contains(normalized);
    }

    private static String normalized(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        return " " + normalized.replaceAll("[^a-z0-9]+", " ").trim() + " ";
    }
}
