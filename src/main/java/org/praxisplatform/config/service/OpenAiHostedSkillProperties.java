package org.praxisplatform.config.service;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Host-owned references to reviewed skills uploaded in the host's OpenAI account.
 * Skill content and customer credentials never belong in the public Praxis request contract.
 */
@Data
@Component
@ConfigurationProperties(prefix = "praxis.ai.openai.hosted-skills")
public class OpenAiHostedSkillProperties {

    private List<Reference> agenticAuthoring = new ArrayList<>();

    public List<Reference> referencesFor(AiExecutionProfile profile) {
        if (profile != AiExecutionProfile.AGENTIC_AUTHORING || agenticAuthoring == null) {
            return List.of();
        }
        return agenticAuthoring.stream()
                .filter(Reference::isValid)
                .toList();
    }

    @Data
    public static class Reference {
        private String id;
        private String version = "latest";

        boolean isValid() {
            return id != null && !id.isBlank();
        }

        String resolvedVersion() {
            return version == null || version.isBlank() ? "latest" : version.trim();
        }
    }
}
