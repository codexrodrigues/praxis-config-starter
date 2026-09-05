package org.praxisplatform.config.ai.authoring;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class AgenticAuthoringResourcePresentationLabel {

    private static final Pattern LEADING_OPERATION_PATTERN = Pattern.compile(
            "(?iu)^(?:filtrar|percorrer|listar|consultar|buscar|obter|carregar|selecionar|navegar por|recuperar|reidratar|localizar|mostrar|exibir)\\s+");
    private static final Pattern LEADING_VIEW_PATTERN = Pattern.compile(
            "(?iu)^(?:vis[aã]o\\s+(?:completa|consolidada|resumida)\\s+de|lista\\s+de|listagem\\s+de|consulta\\s+de)\\s+");
    private static final Pattern QUALIFIER_PATTERN = Pattern.compile(
            "(?iu)\\s+(?:por|em|para|usando|com)\\s+.+$");
    private static final Pattern INTERNAL_DIAGNOSTIC_PATTERN = Pattern.compile(
            "(?iu)\\b(?:llm|authored|governed\\s+resource|resource\\s+focus|semantic\\s+retrieval|api[_\\s-]*metadata|retrieved[_\\s-]*candidate|canonical\\s+filtered\\s+schema|selected\\s+operation|candidate\\s+operation|materialization\\s+endpoint)\\b");
    private static final Pattern GENERIC_OPERATION_LABEL_PATTERN = Pattern.compile(
            "(?iu)^(?:registro|registros|dados|informa[cç][oõ]es|lista|listagem|consulta|resultado|resultados|recurso|recursos)$");

    private AgenticAuthoringResourcePresentationLabel() {
    }

    static String fromCandidate(AgenticAuthoringCandidate candidate) {
        String fallback = fromResourcePath(candidate == null ? "" : candidate.resourcePath());
        String fromEvidence = fromEvidenceSummary(candidate, fallback);
        if (!fromEvidence.isBlank()) {
            return fromEvidence;
        }
        return fallback;
    }

    static String fromResourcePath(String resourcePath) {
        String value = value(resourcePath);
        if (value.isBlank()) {
            return "o recurso selecionado";
        }
        String lastSegment = value.substring(value.lastIndexOf('/') + 1)
                .replace("vw-", "")
                .replace('-', ' ')
                .trim();
        if (lastSegment.isBlank()) {
            return "o recurso selecionado";
        }
        return AgenticAuthoringPresentationText.display(
                Character.toUpperCase(lastSegment.charAt(0)) + lastSegment.substring(1));
    }

    private static String fromEvidenceSummary(AgenticAuthoringCandidate candidate, String fallbackLabel) {
        AgenticAuthoringEvidenceBundle bundle = candidate == null ? null : candidate.evidenceBundle();
        List<AgenticAuthoringEvidenceBundle.Evidence> evidence = bundle == null ? List.of() : bundle.evidence();
        if (evidence == null || evidence.isEmpty()) {
            return "";
        }
        for (AgenticAuthoringEvidenceBundle.Evidence item : evidence) {
            if (item == null || !"api_metadata".equals(value(item.source()))) {
                continue;
            }
            String label = fromSummary(item.summary(), fallbackLabel);
            if (!label.isBlank()) {
                return label;
            }
        }
        return "";
    }

    private static String fromSummary(String summary, String fallbackLabel) {
        String cleaned = value(summary)
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isBlank()
                || "[REDACTED]".equalsIgnoreCase(cleaned)
                || INTERNAL_DIAGNOSTIC_PATTERN.matcher(cleaned).find()) {
            return "";
        }
        String firstSentence = cleaned.split("[\\.;]", 2)[0].trim();
        if (firstSentence.length() > 100) {
            return "";
        }
        String candidate = LEADING_OPERATION_PATTERN.matcher(firstSentence).replaceFirst("").trim();
        candidate = LEADING_VIEW_PATTERN.matcher(candidate).replaceFirst("").trim();
        candidate = QUALIFIER_PATTERN.matcher(candidate).replaceFirst("").trim();
        if (candidate.isBlank() || candidate.length() > 48) {
            candidate = firstSentence;
        }
        if (candidate.length() > 60 || isGenericOperationLabel(candidate, fallbackLabel)) {
            return "";
        }
        return sentenceCase(candidate);
    }

    private static boolean isGenericOperationLabel(String candidate, String fallbackLabel) {
        String normalizedCandidate = value(candidate);
        if (normalizedCandidate.isBlank()) {
            return true;
        }
        if (!GENERIC_OPERATION_LABEL_PATTERN.matcher(normalizedCandidate).matches()) {
            return false;
        }
        String fallback = value(fallbackLabel);
        return !fallback.isBlank() && !"o recurso selecionado".equals(fallback);
    }

    private static String sentenceCase(String value) {
        String cleaned = value(value);
        if (cleaned.isBlank()) {
            return "";
        }
        String lower = cleaned.toLowerCase(Locale.forLanguageTag("pt-BR"));
        return lower.substring(0, 1).toUpperCase(Locale.forLanguageTag("pt-BR")) + lower.substring(1);
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }
}
