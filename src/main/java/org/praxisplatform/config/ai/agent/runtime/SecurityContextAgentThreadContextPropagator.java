package org.praxisplatform.config.ai.agent.runtime;

import java.util.LinkedHashSet;
import java.util.Set;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContext;
import org.praxisplatform.config.ai.agent.spi.AgentExecutionContextCustomizer;
import org.praxisplatform.config.ai.agent.spi.AgentThreadContextPropagator;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;

/**
 * Propaga {@link SecurityContext} entre a thread servlet HTTP e o pool assincrono do assistente
 * operacional e enriquece {@link AgentExecutionContext} com principal e authorities reais.
 */
public class SecurityContextAgentThreadContextPropagator
        implements AgentThreadContextPropagator, AgentExecutionContextCustomizer {

    @Override
    public Snapshot capture() {
        SecurityContext captured = SecurityContextHolder.getContext();
        SecurityContext copy = SecurityContextHolder.createEmptyContext();
        if (captured != null) {
            copy.setAuthentication(captured.getAuthentication());
        }
        return () -> {
            SecurityContext previous = SecurityContextHolder.getContext();
            SecurityContextHolder.setContext(copy);
            return () -> {
                if (previous == null || previous.getAuthentication() == null) {
                    SecurityContextHolder.clearContext();
                } else {
                    SecurityContextHolder.setContext(previous);
                }
            };
        };
    }

    @Override
    public AgentExecutionContext customize(
            AgentExecutionContext current,
            jakarta.servlet.http.HttpServletRequest request) {
        if (current == null) {
            return null;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            return current;
        }
        String principalName = StringUtils.hasText(current.principalName())
                ? current.principalName()
                : auth.getName();
        Set<String> mergedAuthorities = new LinkedHashSet<>(current.authorities());
        if (auth.getAuthorities() != null) {
            for (GrantedAuthority ga : auth.getAuthorities()) {
                if (ga != null && StringUtils.hasText(ga.getAuthority())) {
                    mergedAuthorities.add(ga.getAuthority());
                }
            }
        }
        return new AgentExecutionContext(
                current.threadId(),
                current.turnId(),
                current.streamId(),
                current.tenantId(),
                current.userId(),
                current.environment(),
                principalName,
                mergedAuthorities,
                current.activeScreenResourcePath(),
                current.activeTopicKey(),
                current.activeSelectionHints(),
                current.propagatedClaims());
    }
}
