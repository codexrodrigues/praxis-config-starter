package org.praxisplatform.config.projection;

import org.springframework.beans.factory.annotation.Value;

public interface AiRegistryTemplateSearchProjection {
    String getComponentId();
    String getAiDescription();
    String getConfigJson();
    Double getSimilarityScore();

    @Value("#{(target.configJson != null && target.configJson.length() > 500) ? target.configJson.substring(0, 497) + '...' : target.configJson}")
    String getConfigJsonSnippet();
}
