package org.praxisplatform.config.autoconfig;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;

/**
 * Auto-configures a StrictHttpFirewall that accepts encoded slash/percent so
 * Praxis UI component keys with "/" (sent as %2F) are not rejected with 400.
 *
 * This registers only when the host has not already defined an HttpFirewall and
 * the feature is enabled (default: true).
 */
@AutoConfiguration
@ConditionalOnClass({HttpFirewall.class, StrictHttpFirewall.class})
public class ConfigSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(HttpFirewall.class)
    @ConditionalOnProperty(
            prefix = "praxis.config.firewall",
            name = "allow-encoded-slash",
            havingValue = "true",
            matchIfMissing = true
    )
    public HttpFirewall praxisConfigHttpFirewall() {
        StrictHttpFirewall firewall = new StrictHttpFirewall();
        firewall.setAllowUrlEncodedSlash(true);
        firewall.setAllowUrlEncodedDoubleSlash(true);
        firewall.setAllowUrlEncodedPercent(true);
        firewall.setAllowSemicolon(true);
        return firewall;
    }
}
