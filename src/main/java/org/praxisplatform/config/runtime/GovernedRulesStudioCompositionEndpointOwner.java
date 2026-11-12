package org.praxisplatform.config.runtime;

/**
 * Interface marcadora para controladores HTTP que publicam as rotas canônicas de composição do
 * {@code praxis-rules-studio} ({@code /api/praxis/rules-studio/rule-sets/{ruleSetKey}/candidate*}).
 *
 * <p>Permite que a auto-configuração canônica de {@code GovernedRulesStudioCompositionController}
 * recue automaticamente ({@code @ConditionalOnMissingBean(GovernedRulesStudioCompositionEndpointOwner.class)})
 * quando um host já declara um controlador dedicado sobre o mesmo prefixo de rota, evitando colisão
 * {@code Ambiguous mapping} no Spring MVC.
 */
public interface GovernedRulesStudioCompositionEndpointOwner {}
