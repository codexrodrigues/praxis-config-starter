package org.praxisplatform.config.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.praxisplatform.config.service.DomainRuleGovernancePrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolvedor canônico e fail-closed de escopo de tenant (fronteira soberana de isolamento) e de
 * organização intra-tenant para runtime e governança de regras de negócio ({@code PLAT-EVO-07}).
 *
 * <p>Separa estritamente os cabeçalhos e chaves de fatos de <b>tenant</b> ({@code X-Tenant-ID},
 * {@code X-Tenant} e {@code context.tenantId}) dos cabeçalhos e chaves de <b>organização
 * intra-tenant</b> ({@code X-Organization-ID} e {@code context.organizationId} /
 * {@code context.companyId}), permitindo que sistemas multi-tenant operem simultaneamente com
 * múltiplas empresas/organizações dentro do mesmo tenant sem conflito e bloqueando qualquer
 * divergência ou tentativa de spoofing.
 */
public final class TenantScopeResolver {

    /** Cabeçalhos HTTP canônicos para resolução e validação cruzada de tenant. */
    public static final List<String> TENANT_HEADERS = List.of("X-Tenant-ID", "X-Tenant");

    /** Cabeçalhos HTTP canônicos para resolução e validação de organização intra-tenant. */
    public static final List<String> ORGANIZATION_HEADERS = List.of("X-Organization-ID");

    /** Chaves de escopo de tenant inspecionadas em {@code facts.context}. */
    public static final List<String> TENANT_CONTEXT_KEYS = List.of("tenantId");

    /** Chaves de escopo organizacional intra-tenant inspecionadas em {@code facts.context}. */
    public static final List<String> ORGANIZATION_CONTEXT_KEYS =
            List.of("organizationId", "companyId");

    private TenantScopeResolver() {}

    /**
     * Extrai a dica de tenant dos cabeçalhos HTTP canônicos ({@link #TENANT_HEADERS}), rejeitando
     * com {@code 400 BAD_REQUEST} quando dois cabeçalhos ou múltiplos valores do mesmo cabeçalho
     * divergem entre si.
     */
    public static Optional<String> resolveHeaderTenant(HttpServletRequest request) {
        return resolveConsistentHeader(
                request, TENANT_HEADERS, "Cabeçalhos de escopo de tenant divergentes");
    }

    /**
     * Extrai a dica de organização intra-tenant dos cabeçalhos HTTP canônicos ({@link
     * #ORGANIZATION_HEADERS}), rejeitando com {@code 400 BAD_REQUEST} quando múltiplos valores
     * divergem entre si.
     */
    public static Optional<String> resolveHeaderOrganization(HttpServletRequest request) {
        return resolveConsistentHeader(
                request,
                ORGANIZATION_HEADERS,
                "Cabeçalhos de escopo organizacional intra-tenant divergentes");
    }

    private static Optional<String> resolveConsistentHeader(
            HttpServletRequest request, List<String> headers, String conflictPrefix) {
        if (request == null) {
            return Optional.empty();
        }
        String resolved = null;
        String resolvedHeader = null;
        for (String header : headers) {
            Enumeration<String> values = request.getHeaders(header);
            if (values != null) {
                while (values.hasMoreElements()) {
                    String raw = values.nextElement();
                    if (raw == null || raw.isBlank()) {
                        continue;
                    }
                    String trimmed = raw.trim();
                    if (resolved == null) {
                        resolved = trimmed;
                        resolvedHeader = header;
                    } else if (!resolved.equals(trimmed)) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST,
                                conflictPrefix
                                        + " entre '"
                                        + resolvedHeader
                                        + "' e '"
                                        + header
                                        + "'.");
                    }
                }
            } else {
                String raw = request.getHeader(header);
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String trimmed = raw.trim();
                if (resolved == null) {
                    resolved = trimmed;
                    resolvedHeader = header;
                } else if (!resolved.equals(trimmed)) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            conflictPrefix + " entre '" + resolvedHeader + "' e '" + header + "'.");
                }
            }
        }
        return Optional.ofNullable(resolved);
    }

    /**
     * Valida que o tenant solicitado (quando informado) coincide exatamente com o
     * {@link DomainRuleGovernancePrincipal#tenantId()}, lançando {@code 403 FORBIDDEN} em caso de
     * divergência.
     */
    public static String requireAuthorizedTenant(
            String requestedTenant, DomainRuleGovernancePrincipal principal) {
        Objects.requireNonNull(principal, "principal");
        if (principal.tenantId() == null || principal.tenantId().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Escopo de tenant autenticado ausente.");
        }
        String authorized = principal.tenantId().trim();
        if (requestedTenant != null
                && !requestedTenant.isBlank()
                && !requestedTenant.trim().equals(authorized)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Escopo de tenant solicitado diverge do principal autenticado.");
        }
        return authorized;
    }

    /** Token canônico para escopo consolidado de todas as organizações autorizadas no tenant. */
    public static final String ALL_ORGANIZATIONS_TOKEN = "ALL";

    /** Prefixo canônico de seleção explícita de organização individual ({@code ORG:<id>}). */
    public static final String SINGLE_ORGANIZATION_PREFIX = "ORG:";

    /** Prefixo canônico de seleção de grupo de organizações intra-tenant ({@code GROUP:<key>}). */
    public static final String ORGANIZATION_GROUP_PREFIX = "GROUP:";

    /**
     * Valida que a organização intra-tenant solicitada (quando informada) pertence ao conjunto de
     * organizações autorizadas para o operador dentro do tenant ativo, lançando {@code 403
     * FORBIDDEN} caso contrário.
     *
     * <p>Reconhece o token consolidado {@link #ALL_ORGANIZATIONS_TOKEN} (exigindo ao menos uma
     * organização autorizada e retornando {@link Optional#empty()} para indicar escopo amplo do
     * tenant) e normaliza o prefixo explícito {@link #SINGLE_ORGANIZATION_PREFIX} ({@code ORG:2 ->
     * 2}).
     */
    public static Optional<String> requireAuthorizedOrganization(
            String requestedOrganization, Set<String> authorizedOrganizationIds) {
        if (requestedOrganization == null || requestedOrganization.isBlank()) {
            return Optional.empty();
        }
        String candidate = requestedOrganization.trim();
        Set<String> normalized = normalizeScopeSet(authorizedOrganizationIds);
        if (ALL_ORGANIZATIONS_TOKEN.equals(candidate)) {
            if (normalized.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Nenhuma organização intra-tenant autorizada para escopo consolidado.");
            }
            return Optional.empty();
        }
        if (candidate.startsWith(SINGLE_ORGANIZATION_PREFIX)
                && candidate.length() > SINGLE_ORGANIZATION_PREFIX.length()) {
            String unwrapped = candidate.substring(SINGLE_ORGANIZATION_PREFIX.length()).trim();
            if (!unwrapped.isEmpty()
                    && (normalized.contains(unwrapped) || normalized.contains(candidate))) {
                return Optional.of(unwrapped);
            }
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Organização intra-tenant solicitada não está autorizada para o principal autenticado.");
        }
        if (normalized.isEmpty() || !normalized.contains(candidate)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Organização intra-tenant solicitada não está autorizada para o principal autenticado.");
        }
        return Optional.of(candidate);
    }

    /**
     * Resolve e valida o conjunto efetivo de organizações intra-tenant para tokens unitários
     * ({@code <id>} ou {@code ORG:<id>}), escopo consolidado ({@code ALL} ou ausente) e grupos de
     * organizações ({@code GROUP:<key>}), exigindo de forma fail-closed que 100% dos membros do
     * grupo pertençam às organizações autorizadas do operador.
     */
    public static Set<String> requireAuthorizedOrganizationScope(
            String requestedOrganization,
            Set<String> authorizedOrganizationIds,
            Function<String, Optional<Set<String>>> groupMembersResolver) {
        Set<String> normalized = normalizeScopeSet(authorizedOrganizationIds);
        if (normalized.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Principal autenticado não possui organizações intra-tenant autorizadas.");
        }
        if (requestedOrganization == null || requestedOrganization.isBlank()) {
            return normalized;
        }
        String candidate = requestedOrganization.trim();
        if (ALL_ORGANIZATIONS_TOKEN.equals(candidate)) {
            return normalized;
        }
        if (candidate.startsWith(ORGANIZATION_GROUP_PREFIX)
                && candidate.length() > ORGANIZATION_GROUP_PREFIX.length()) {
            String groupKey = candidate.substring(ORGANIZATION_GROUP_PREFIX.length()).trim();
            Set<String> members =
                    groupMembersResolver != null
                            ? groupMembersResolver
                                    .apply(groupKey)
                                    .map(TenantScopeResolver::normalizeScopeSet)
                                    .orElse(Set.of())
                            : Set.of();
            if (members.isEmpty() || !normalized.containsAll(members)) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Grupo organizacional solicitado é inválido, inativo ou excede as organizações autorizadas.");
            }
            return Set.copyOf(new LinkedHashSet<>(members));
        }
        String single =
                requireAuthorizedOrganization(candidate, normalized)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.FORBIDDEN,
                                                "Organização intra-tenant solicitada inválida."));
        return Set.of(single);
    }

    /**
     * Valida todas as chaves de escopo de tenant ({@link #TENANT_CONTEXT_KEYS}) presentes em
     * {@code facts.context} contra o {@code authorizedTenantId}, falhando fechado com
     * {@link IllegalArgumentException} em qualquer divergência.
     */
    public static void verifyFactsContextScope(JsonNode contextNode, String authorizedTenantId) {
        if (authorizedTenantId == null || authorizedTenantId.isBlank()) {
            throw new IllegalArgumentException("authorizedTenantId is required");
        }
        if (contextNode == null || !contextNode.isObject()) {
            return;
        }
        String expected = authorizedTenantId.trim();
        for (String tenantKey : TENANT_CONTEXT_KEYS) {
            JsonNode ctxScopeVal = contextNode.get(tenantKey);
            if (ctxScopeVal != null
                    && !ctxScopeVal.isNull()
                    && !ctxScopeVal.asText().isBlank()
                    && !ctxScopeVal.asText().trim().equals(expected)) {
                throw new IllegalArgumentException(
                        "Escopo de tenant '"
                                + tenantKey
                                + "' em context diverge do tenant autorizado");
            }
        }
    }

    /**
     * Valida em dois níveis a árvore {@code facts.context}: primeiro garante que o escopo de tenant
     * ({@link #TENANT_CONTEXT_KEYS}) coincide com {@code authorizedTenantId} e, quando {@code
     * authorizedOrganizationIds} for informado, exige que qualquer chave organizacional intra-tenant
     * ({@link #ORGANIZATION_CONTEXT_KEYS}) pertença ao conjunto autorizado.
     */
    public static void verifyFactsContextScope(
            JsonNode contextNode,
            String authorizedTenantId,
            Set<String> authorizedOrganizationIds) {
        verifyFactsContextScope(contextNode, authorizedTenantId);
        if (authorizedOrganizationIds == null || contextNode == null || !contextNode.isObject()) {
            return;
        }
        Set<String> normalized = normalizeScopeSet(authorizedOrganizationIds);
        for (String orgKey : ORGANIZATION_CONTEXT_KEYS) {
            JsonNode ctxOrgVal = contextNode.get(orgKey);
            if (ctxOrgVal != null && !ctxOrgVal.isNull() && !ctxOrgVal.asText().isBlank()) {
                String actual = ctxOrgVal.asText().trim();
                if (!normalized.contains(actual)) {
                    throw new IllegalArgumentException(
                            "Escopo organizacional '"
                                    + orgKey
                                    + "' em context não pertence às organizações autorizadas do tenant");
                }
            }
        }
    }

    private static Set<String> normalizeScopeSet(Set<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        return raw.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
