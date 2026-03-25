package org.praxisplatform.config.service;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.security.Principal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolve a identidade operacional canonica do fluxo AI a partir do principal autenticado,
 * atributos do request e hints opcionais de header.
 *
 * <p>Em modo corporativo, tenant e usuario devem ser resolvidos preferencialmente pelo lado
 * servidor; hints de caller nao substituem a identidade autenticada. Em modo local, o resolver
 * pode aplicar defaults de desenvolvimento e, quando habilitado, aceitar hints de header para
 * simular contexto multi-tenant de forma controlada.
 */
@Service
public class AiPrincipalContextResolver {

    private static final List<String> TENANT_KEYS = List.of(
            "tenantId",
            "tenant_id",
            "tenant",
            "X-Tenant-ID",
            "x-tenant-id");
    private static final List<String> USER_KEYS = List.of(
            "userId",
            "user_id",
            "user",
            "sub",
            "preferred_username",
            "username",
            "X-User-ID",
            "x-user-id");
    private static final List<String> ENV_KEYS = List.of(
            "environment",
            "env",
            "X-Env",
            "x-env");
    private static final Set<String> ANONYMOUS_IDENTITIES = Set.of(
            "anonymoususer",
            "anonymous",
            "guest");

    private final boolean corporateMode;
    private final boolean allowHeaderIdentityInLocal;
    private final String localDefaultTenant;
    private final String localDefaultUser;
    private final String localDefaultEnvironment;
    private final String serverDefaultEnvironment;

    @Value("${praxis.ai.security.allow-default-tenant-in-corporate:false}")
    private boolean allowDefaultTenantInCorporate;

    @Value("${praxis.ai.security.server-default-tenant:}")
    private String serverDefaultTenant;

    public AiPrincipalContextResolver(
            @Value("${praxis.ai.security.corporate-mode:true}") boolean corporateMode,
            @Value("${praxis.ai.security.allow-header-identity-in-local:false}") boolean allowHeaderIdentityInLocal,
            @Value("${praxis.ai.security.local-default-tenant:demo}") String localDefaultTenant,
            @Value("${praxis.ai.security.local-default-user:demo}") String localDefaultUser,
            @Value("${praxis.ai.security.local-default-environment:local}") String localDefaultEnvironment,
            @Value("${praxis.ai.security.server-default-environment:prod}") String serverDefaultEnvironment) {
        this.corporateMode = corporateMode;
        this.allowHeaderIdentityInLocal = allowHeaderIdentityInLocal;
        this.localDefaultTenant = normalize(localDefaultTenant);
        this.localDefaultUser = normalize(localDefaultUser);
        this.localDefaultEnvironment = normalize(localDefaultEnvironment);
        this.serverDefaultEnvironment = normalize(serverDefaultEnvironment);
    }

    public AiPrincipalContext resolve(
            HttpServletRequest request,
            String tenantHeaderHint,
            String userHeaderHint,
            String environmentHeaderHint) {
        String principalTenant = firstNonBlank(
                readRequestAttribute(request, TENANT_KEYS),
                readSecurityContextAttribute(TENANT_KEYS),
                readPrincipalAttribute(request != null ? request.getUserPrincipal() : null, TENANT_KEYS));
        String principalUser = firstNonBlank(
                readPrincipalUser(request),
                readSecurityContextUser(),
                readRequestAttribute(request, USER_KEYS),
                readPrincipalAttribute(request != null ? request.getUserPrincipal() : null, USER_KEYS));
        String principalEnvironment = firstNonBlank(
                readRequestAttribute(request, ENV_KEYS),
                readSecurityContextAttribute(ENV_KEYS),
                readPrincipalAttribute(request != null ? request.getUserPrincipal() : null, ENV_KEYS));

        if (corporateMode) {
            String resolvedCorporateTenant = principalTenant;
            if (resolvedCorporateTenant == null && allowDefaultTenantInCorporate) {
                resolvedCorporateTenant = normalize(serverDefaultTenant);
            }
            if (resolvedCorporateTenant == null || principalUser == null) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Corporate identity is required and must be resolved server-side.");
            }
            return new AiPrincipalContext(
                    resolvedCorporateTenant,
                    principalUser,
                    firstNonBlank(principalEnvironment, serverDefaultEnvironment),
                    true);
        }

        String resolvedTenant;
        String resolvedUser;
        String resolvedEnvironment;
        if (allowHeaderIdentityInLocal) {
            resolvedTenant = firstNonBlank(principalTenant, tenantHeaderHint, localDefaultTenant);
            resolvedUser = firstNonBlank(principalUser, userHeaderHint, localDefaultUser);
            resolvedEnvironment = firstNonBlank(principalEnvironment, environmentHeaderHint, localDefaultEnvironment);
        } else {
            resolvedTenant = firstNonBlank(principalTenant, localDefaultTenant);
            resolvedUser = firstNonBlank(principalUser, localDefaultUser);
            resolvedEnvironment = firstNonBlank(principalEnvironment, localDefaultEnvironment);
        }

        return new AiPrincipalContext(
                resolvedTenant,
                resolvedUser,
                resolvedEnvironment,
                principalTenant != null || principalUser != null);
    }

    private String readPrincipalUser(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Principal principal = request.getUserPrincipal();
        if (principal == null) {
            return null;
        }
        String byName = normalizeUser(principal.getName());
        if (byName != null) {
            return byName;
        }
        String nestedPrincipal = readNestedPrincipalAttribute(principal, USER_KEYS);
        if (nestedPrincipal != null) {
            return normalizeUser(nestedPrincipal);
        }
        return normalizeUser(readPrincipalAttribute(principal, USER_KEYS));
    }

    private String readRequestAttribute(HttpServletRequest request, List<String> keys) {
        if (request == null || keys == null || keys.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            Object value = request.getAttribute(key);
            String normalized = normalize(asString(value));
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String readPrincipalAttribute(Principal principal, List<String> keys) {
        if (principal == null || keys == null || keys.isEmpty()) {
            return null;
        }
        if (principal instanceof Map<?, ?> principalMap) {
            String fromMap = findInMap(principalMap, keys);
            if (fromMap != null) {
                return fromMap;
            }
        }
        String fromAttributesMethod = readFromMethodReturningMap(principal, "getAttributes", keys);
        if (fromAttributesMethod != null) {
            return fromAttributesMethod;
        }
        String fromClaimsMethod = readFromMethodReturningMap(principal, "getClaims", keys);
        if (fromClaimsMethod != null) {
            return fromClaimsMethod;
        }
        String fromDetailsMethod = readFromMethodReturningMap(principal, "getDetails", keys);
        if (fromDetailsMethod != null) {
            return fromDetailsMethod;
        }
        String fromTokenAttributes = readFromMethodReturningMap(principal, "getTokenAttributes", keys);
        if (fromTokenAttributes != null) {
            return fromTokenAttributes;
        }
        String fromNestedPrincipal = readNestedPrincipalAttribute(principal, keys);
        if (fromNestedPrincipal != null) {
            return fromNestedPrincipal;
        }
        return null;
    }

    private String readNestedPrincipalAttribute(Principal principal, List<String> keys) {
        if (principal == null) {
            return null;
        }
        Object nested = invokeNoArgMethod(principal, "getPrincipal");
        if (nested == null) {
            return null;
        }
        if (nested instanceof Principal nestedPrincipal) {
            return firstNonBlank(
                    normalize(nestedPrincipal.getName()),
                    readPrincipalAttribute(nestedPrincipal, keys));
        }
        if (nested instanceof Map<?, ?> map) {
            return findInMap(map, keys);
        }
        return readFromMethodReturningMap(nested, "getAttributes", keys);
    }

    @SuppressWarnings("unchecked")
    private String readFromMethodReturningMap(Object target, String methodName, List<String> keys) {
        try {
            Method method = target.getClass().getMethod(methodName);
            Object value = method.invoke(target);
            if (value instanceof Map<?, ?> mapValue) {
                return findInMap((Map<Object, Object>) mapValue, keys);
            }
        } catch (Exception ignored) {
            // Best-effort extraction to avoid hard coupling with security implementations.
        }
        return null;
    }

    private String readSecurityContextUser() {
        Object authentication = getSecurityContextAuthentication();
        if (authentication == null) {
            return null;
        }
        String byName = normalizeUser(asString(invokeNoArgMethod(authentication, "getName")));
        if (byName != null) {
            return byName;
        }
        Object principal = invokeNoArgMethod(authentication, "getPrincipal");
        if (principal instanceof Principal principalValue) {
            return normalizeUser(principalValue.getName());
        }
        if (principal instanceof Map<?, ?> map) {
            return normalizeUser(findInMap(map, USER_KEYS));
        }
        if (principal != null) {
            String fromAttributes = normalizeUser(readFromMethodReturningMap(principal, "getAttributes", USER_KEYS));
            if (fromAttributes != null) {
                return fromAttributes;
            }
            String fromClaims = normalizeUser(readFromMethodReturningMap(principal, "getClaims", USER_KEYS));
            if (fromClaims != null) {
                return fromClaims;
            }
            String fromTokenAttributes = normalizeUser(readFromMethodReturningMap(principal, "getTokenAttributes", USER_KEYS));
            if (fromTokenAttributes != null) {
                return fromTokenAttributes;
            }
        }
        return null;
    }

    private String readSecurityContextAttribute(List<String> keys) {
        Object authentication = getSecurityContextAuthentication();
        if (authentication == null) {
            return null;
        }
        String fromDetails = readFromMethodReturningMap(authentication, "getDetails", keys);
        if (fromDetails != null) {
            return fromDetails;
        }
        Object principal = invokeNoArgMethod(authentication, "getPrincipal");
        if (principal instanceof Map<?, ?> map) {
            String fromMap = findInMap(map, keys);
            if (fromMap != null) {
                return fromMap;
            }
        }
        if (principal != null) {
            String fromAttributes = readFromMethodReturningMap(principal, "getAttributes", keys);
            if (fromAttributes != null) {
                return fromAttributes;
            }
            String fromClaims = readFromMethodReturningMap(principal, "getClaims", keys);
            if (fromClaims != null) {
                return fromClaims;
            }
            String fromTokenAttributes = readFromMethodReturningMap(principal, "getTokenAttributes", keys);
            if (fromTokenAttributes != null) {
                return fromTokenAttributes;
            }
        }
        return null;
    }

    private Object getSecurityContextAuthentication() {
        try {
            Class<?> holderClass = Class.forName("org.springframework.security.core.context.SecurityContextHolder");
            Method getContext = holderClass.getMethod("getContext");
            Object context = getContext.invoke(null);
            if (context == null) {
                return null;
            }
            Method getAuthentication = context.getClass().getMethod("getAuthentication");
            return getAuthentication.invoke(context);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object invokeNoArgMethod(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String findInMap(Map<?, ?> map, List<String> keys) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        for (String key : keys) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String mapKey = asString(entry.getKey());
                if (mapKey == null) {
                    continue;
                }
                if (mapKey.equalsIgnoreCase(key)) {
                    String normalized = normalize(asString(entry.getValue()));
                    if (normalized != null) {
                        return normalized;
                    }
                }
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = normalize(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeUser(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        String key = normalized.toLowerCase(Locale.ROOT);
        return ANONYMOUS_IDENTITIES.contains(key) ? null : normalized;
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String str) {
            return str;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        String serialized = value.toString();
        if (serialized == null) {
            return null;
        }
        String lower = serialized.toLowerCase(Locale.ROOT);
        return "null".equals(lower) ? null : serialized;
    }
}
