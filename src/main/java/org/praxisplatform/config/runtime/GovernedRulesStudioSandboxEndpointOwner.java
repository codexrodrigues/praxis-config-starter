package org.praxisplatform.config.runtime;

/**
 * Interface marcadora para controladores HTTP que publicam as rotas de sandbox e prova operacional
 * do Policy/Rules Studio ({@code /api/praxis/rules-studio/sandbox/runs} e {@code
 * /api/praxis/rules-studio/actions/run-rules-studio-operational-test}, além de {@code
 * /api/praxis/policy-studio/**}).
 *
 * <p>Permite que a auto-configuração canônica de {@code GovernedRulesStudioSandboxController} recue
 * automaticamente ({@code @ConditionalOnMissingBean(GovernedRulesStudioSandboxEndpointOwner.class)})
 * quando um host já declara um controlador dedicado sobre os mesmos caminhos HTTP, evitando colisão
 * {@code Ambiguous mapping} no Spring MVC.
 */
public interface GovernedRulesStudioSandboxEndpointOwner {}
