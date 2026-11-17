package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

/**
 * Guarda determinística que impede a persistência em escopo compartilhado {@code tenant} de
 * critérios ou valores de filtro sobre campos pessoais, sensíveis, financeiros ou restritos.
 *
 * <p>Combina uma matriz base canônica de chaves sensíveis, extensões configuradas pelo host e chaves
 * descobertas dinamicamente a partir de {@code @DomainGovernance} + {@code @Filterable} quando
 * {@code praxis-metadata-starter} está presente no classpath.
 */
public class SensitiveTenantConfigGuard {

    public static final String SENSITIVE_CRITERIA_ERROR_CODE = "SHARED_CONFIG_SENSITIVE_CRITERIA_DENIED";

    private static final Pattern CPF_VALUE_PATTERN =
            Pattern.compile("^(?:\\d{11}|\\d{3}\\.\\d{3}\\.\\d{3}-\\d{2})$");
    private static final Pattern EMAIL_VALUE_PATTERN =
            Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");

    private static final Set<String> CRITERIA_CONTAINER_KEYS = Set.of(
            "criteria",
            "filtercriteria",
            "defaultfilter",
            "defaultcriteria",
            "filters",
            "filter",
            "initialvalue",
            "initialvalues",
            "defaultvalues",
            "queryparams",
            "values");

    private static final Set<String> DEFAULT_SENSITIVE_KEYS = Set.of(
            "cpf",
            "cnpj",
            "cpfcnpj",
            "taxid",
            "ssn",
            "pis",
            "pasep",
            "pispasep",
            "nit",
            "rg",
            "cnh",
            "ctps",
            "tituloeleitor",
            "passaporte",
            "passaportenumero",
            "passport",
            "nomemae",
            "mothername",
            "nomepai",
            "fathername",
            "datanascimento",
            "birthdate",
            "nascimento",
            "salario",
            "salariobase",
            "remuneracao",
            "remuneracaobruta",
            "remuneracaoliquida",
            "salary",
            "vencimento",
            "contabancaria",
            "bankaccount",
            "agencia",
            "pix",
            "chavepix",
            "email",
            "emailpessoal",
            "emailcorporativo",
            "telefone",
            "celular",
            "phone",
            "mobile",
            "endereco",
            "logradouro",
            "cep",
            "address",
            "raca",
            "racacor",
            "cor",
            "etnia",
            "deficiencia",
            "pcd",
            "cid",
            "religiao",
            "filiacaosindical",
            "biometria",
            "password",
            "senha",
            "secret",
            "token",
            "apikey",
            "privatekey",
            "credentials");

    private final boolean blockSensitiveTenantCriteria;
    private final Set<String> baseSensitiveKeys;
    private final List<SensitiveCriteriaDiscoverySource> discoverySources;
    private volatile Set<String> cachedEffectiveKeys;

    public SensitiveTenantConfigGuard(
            boolean blockSensitiveTenantCriteria,
            Collection<String> additionalKeys,
            List<SensitiveCriteriaDiscoverySource> discoverySources) {
        this.blockSensitiveTenantCriteria = blockSensitiveTenantCriteria;
        Set<String> keys = new LinkedHashSet<>(DEFAULT_SENSITIVE_KEYS);
        if (additionalKeys != null) {
            for (String key : additionalKeys) {
                String normalized = normalizeKey(key);
                if (StringUtils.hasText(normalized)) {
                    keys.add(normalized);
                }
            }
        }
        this.baseSensitiveKeys = Collections.unmodifiableSet(keys);
        this.discoverySources =
                discoverySources == null ? List.of() : List.copyOf(discoverySources);
    }

    /**
     * Valida a requisição de escrita em {@code ui_user_config}, lançando
     * {@link UiConfigWriteDeniedException} com HTTP {@code 422} e {@code errorCode}
     * {@link #SENSITIVE_CRITERIA_ERROR_CODE} quando um filtro compartilhado {@code tenant}
     * contiver critérios sensíveis.
     */
    public void assertAllowed(UiConfigWriteAuthorizationRequest request) {
        if (!blockSensitiveTenantCriteria || request == null) {
            return;
        }
        if (!"tenant".equalsIgnoreCase(request.scope())
                || !"upsert".equalsIgnoreCase(request.operation())) {
            return;
        }
        if (containsSensitiveTenantConfigContent(request.payload())) {
            throw new UiConfigWriteDeniedException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    SENSITIVE_CRITERIA_ERROR_CODE,
                    "Shared tenant configurations cannot persist criteria or default values over "
                            + "personal, sensitive, financial, or security-governed fields.");
        }
    }

    public boolean containsSensitiveTenantConfigContent(JsonNode payload) {
        if (payload == null || payload.isNull() || payload.isMissingNode()) {
            return false;
        }
        Set<String> sensitiveKeys = effectiveSensitiveKeys();
        return scanNode(payload, null, false, sensitiveKeys);
    }

    public Set<String> effectiveSensitiveKeys() {
        Set<String> cached = this.cachedEffectiveKeys;
        if (cached != null) {
            return cached;
        }
        Set<String> merged = new LinkedHashSet<>(baseSensitiveKeys);
        for (SensitiveCriteriaDiscoverySource source : discoverySources) {
            Set<String> discovered = source.discoverNormalizedSensitiveKeys();
            if (discovered != null) {
                for (String key : discovered) {
                    String normalized = normalizeKey(key);
                    if (StringUtils.hasText(normalized)) {
                        merged.add(normalized);
                    }
                }
            }
        }
        Set<String> immutable = Collections.unmodifiableSet(merged);
        this.cachedEffectiveKeys = immutable;
        return immutable;
    }

    private boolean scanNode(
            JsonNode node,
            String currentFieldName,
            boolean insideCriteriaSubtree,
            Set<String> sensitiveKeys) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return false;
        }
        if (node.isObject()) {
            // Handle structured field-value criterion objects like {"field": "cpf", "value": "123"}
            JsonNode fieldNode = node.get("field");
            if (fieldNode != null && fieldNode.isTextual() && hasNonEmptyCriterionValue(node)) {
                if (isSensitiveKey(fieldNode.asText(), sensitiveKeys)) {
                    return true;
                }
            }
            JsonNode nameNode = node.get("name");
            if (nameNode != null && nameNode.isTextual() && insideCriteriaSubtree && hasNonEmptyCriterionValue(node)) {
                if (isSensitiveKey(nameNode.asText(), sensitiveKeys)) {
                    return true;
                }
            }

            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                String normalizedKey = normalizeKey(key);
                boolean childCriteriaContext =
                        insideCriteriaSubtree || CRITERIA_CONTAINER_KEYS.contains(normalizedKey);
                if (isSensitiveKey(key, sensitiveKeys) && hasMeaningfulValue(value)) {
                    return true;
                }
                if (scanNode(value, key, childCriteriaContext, sensitiveKeys)) {
                    return true;
                }
            }
            return false;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (scanNode(item, currentFieldName, insideCriteriaSubtree, sensitiveKeys)) {
                    return true;
                }
            }
            return false;
        }
        if (insideCriteriaSubtree && node.isTextual()) {
            String text = node.asText("").trim();
            if (!text.isEmpty()
                    && (CPF_VALUE_PATTERN.matcher(text).matches()
                            || EMAIL_VALUE_PATTERN.matcher(text).matches())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNonEmptyCriterionValue(JsonNode node) {
        if (node.has("value") && hasMeaningfulValue(node.get("value"))) {
            return true;
        }
        if (node.has("values") && hasMeaningfulValue(node.get("values"))) {
            return true;
        }
        return node.has("criteria") && hasMeaningfulValue(node.get("criteria"));
    }

    private static boolean hasMeaningfulValue(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return false;
        }
        if (value.isTextual()) {
            return !value.asText().trim().isEmpty();
        }
        if (value.isArray() || value.isObject()) {
            if (value.isEmpty()) {
                return false;
            }
            if (value.isArray()) {
                for (JsonNode child : value) {
                    if (hasMeaningfulValue(child)) {
                        return true;
                    }
                }
                return false;
            }
            Iterator<JsonNode> elements = value.elements();
            while (elements.hasNext()) {
                if (hasMeaningfulValue(elements.next())) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    static boolean isSensitiveKey(String rawKey, Set<String> sensitiveKeys) {
        String normalized = normalizeKey(rawKey);
        if (!StringUtils.hasText(normalized)) {
            return false;
        }
        if (sensitiveKeys.contains(normalized)) {
            return true;
        }
        int lastDot = rawKey.lastIndexOf('.');
        if (lastDot >= 0 && lastDot + 1 < rawKey.length()) {
            String leaf = normalizeKey(rawKey.substring(lastDot + 1));
            return sensitiveKeys.contains(leaf);
        }
        return false;
    }

    static String normalizeKey(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != '_' && c != '-' && !Character.isWhitespace(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }
}
