package org.praxisplatform.config.autoconfig;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringApplyService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringArtifactProperties;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringArtifactSource;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringDryRunReportService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringDryRunService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringIntentResolverService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPatchCompilerService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPlanService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringPreviewService;
import org.praxisplatform.config.ai.authoring.AgenticAuthoringReplayAuditService;
import org.praxisplatform.config.service.AiApiKeyProtectionService;
import org.praxisplatform.config.service.AiProviderManagementService;
import org.praxisplatform.config.service.AiTurnEventService;
import org.praxisplatform.config.service.UserConfigService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("unit")
class AgenticAuthoringAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgenticAuthoringAutoConfiguration.class))
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void shouldRegisterInternalAuthoringBeansWithoutStartupRunnerByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AgenticAuthoringArtifactProperties.class);
            assertThat(context.getBean(AgenticAuthoringArtifactProperties.class).isHttpEnabled()).isFalse();
            assertThat(context).hasSingleBean(AgenticAuthoringArtifactSource.class);
            assertThat(context).hasSingleBean(AgenticAuthoringDryRunService.class);
            assertThat(context).hasSingleBean(AgenticAuthoringIntentResolverService.class);
            assertThat(context).hasSingleBean(AgenticAuthoringPatchCompilerService.class);
            assertThat(context).hasSingleBean(AgenticAuthoringDryRunReportService.class);
            assertThat(context).doesNotHaveBean(AgenticAuthoringPlanService.class);
            assertThat(context).doesNotHaveBean(AgenticAuthoringPreviewService.class);
            assertThat(context).doesNotHaveBean(AgenticAuthoringReplayAuditService.class);
            assertThat(context).doesNotHaveBean(ApplicationRunner.class);
        });
    }

    @Test
    void shouldRegisterDryRunRunnerOnlyWhenExplicitlyEnabled() {
        contextRunner
                .withPropertyValues(
                        "praxis.ai.authoring.dry-run-enabled=true",
                        "praxis.ai.authoring.artifacts-dir=D:/Developer/praxis-plataform/docs/ai/agentic-authoring/proofs",
                        "praxis.ai.authoring.report-path=build/agentic-authoring/dry-run-report.json"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ApplicationRunner.class);
                    AgenticAuthoringArtifactProperties properties = context.getBean(AgenticAuthoringArtifactProperties.class);
                    assertThat(properties.isDryRunEnabled()).isTrue();
                    assertThat(properties.isHttpEnabled()).isFalse();
                    assertThat(properties.getArtifactsDir()).isNotNull();
                    assertThat(properties.getReportPath()).isNotNull();
                });
    }

    @Test
    void shouldBindHttpEndpointFlagWithoutEnablingStartupRunner() {
        contextRunner
                .withPropertyValues("praxis.ai.authoring.http-enabled=true")
                .run(context -> {
                    AgenticAuthoringArtifactProperties properties = context.getBean(AgenticAuthoringArtifactProperties.class);
                    assertThat(properties.isHttpEnabled()).isTrue();
                    assertThat(context).doesNotHaveBean(ApplicationRunner.class);
                });
    }

    @Test
    void shouldRespectUserProvidedDryRunService() {
        AgenticAuthoringDryRunService customService = new AgenticAuthoringDryRunService(new ObjectMapper());
        contextRunner
                .withBean(AgenticAuthoringDryRunService.class, () -> customService)
                .run(context -> assertThat(context.getBean(AgenticAuthoringDryRunService.class)).isSameAs(customService));
    }

    @Test
    void shouldRegisterReplayAuditServiceWhenTurnEventServiceExists() {
        AiTurnEventService turnEventService = org.mockito.Mockito.mock(AiTurnEventService.class);
        contextRunner
                .withBean(AiTurnEventService.class, () -> turnEventService)
                .run(context -> assertThat(context).hasSingleBean(AgenticAuthoringReplayAuditService.class));
    }

    @Test
    void shouldRegisterPlanServiceWhenProviderManagementExists() {
        AiProviderManagementService providerManagementService = org.mockito.Mockito.mock(AiProviderManagementService.class);
        contextRunner
                .withBean(AiProviderManagementService.class, () -> providerManagementService)
                .run(context -> {
                    assertThat(context).hasSingleBean(AgenticAuthoringPlanService.class);
                    assertThat(context).hasSingleBean(AgenticAuthoringPreviewService.class);
                });
    }

    @Test
    void shouldRegisterApplyServiceWhenUserConfigServiceExists() {
        UserConfigService userConfigService = org.mockito.Mockito.mock(UserConfigService.class);
        AiApiKeyProtectionService apiKeyProtectionService = org.mockito.Mockito.mock(AiApiKeyProtectionService.class);
        contextRunner
                .withBean(UserConfigService.class, () -> userConfigService)
                .withBean(AiApiKeyProtectionService.class, () -> apiKeyProtectionService)
                .run(context -> assertThat(context).hasSingleBean(AgenticAuthoringApplyService.class));
    }
}
