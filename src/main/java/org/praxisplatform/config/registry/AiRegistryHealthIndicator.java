package org.praxisplatform.config.registry;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

@RequiredArgsConstructor
public class AiRegistryHealthIndicator implements HealthIndicator {

    private final AiRegistryStatusService statusService;

    @Override
    public Health health() {
        AiRegistryStatusReport report = statusService.getStatus();
        Health.Builder builder = report.isReady() ? Health.up() : Health.down();
        builder.withDetail("ready", report.isReady())
                .withDetail("status", report.getStatus())
                .withDetail("componentDefinitionCount", report.getComponentDefinitionCount())
                .withDetail("templateCount", report.getTemplateCount())
                .withDetail("minComponentDefinitions", report.getMinComponentDefinitions())
                .withDetail("minTemplates", report.getMinTemplates())
                .withDetail("requiredComponents", report.getRequiredComponents())
                .withDetail("missingComponents", report.getMissingComponents())
                .withDetail("bootstrap", report.getBootstrap());
        return builder.build();
    }
}
