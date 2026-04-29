package org.praxisplatform.config.autoconfig;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

@AutoConfiguration
@ConditionalOnClass({JdbcTemplate.class, NamedParameterJdbcTemplate.class})
public class ConfigJdbcTemplateAutoConfiguration {

    @Bean(name = "configNamedParameterJdbcTemplate")
    @ConditionalOnMissingBean(name = "configNamedParameterJdbcTemplate")
    public NamedParameterJdbcTemplate configNamedParameterJdbcTemplate(ApplicationContext context) {
        if (context.containsBean("configJdbcTemplate")) {
            return new NamedParameterJdbcTemplate(context.getBean("configJdbcTemplate", JdbcTemplate.class));
        }
        return new NamedParameterJdbcTemplate(context.getBean(JdbcTemplate.class));
    }
}
