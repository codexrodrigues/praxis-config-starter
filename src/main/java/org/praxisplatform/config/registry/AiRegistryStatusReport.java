package org.praxisplatform.config.registry;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiRegistryStatusReport {

    private boolean ready;
    private String status;

    private long componentDefinitionCount;
    private long templateCount;

    private long minComponentDefinitions;
    private long minTemplates;

    private List<String> requiredComponents;
    private List<String> missingComponents;

    private AiRegistryBootstrapState bootstrap;
}
