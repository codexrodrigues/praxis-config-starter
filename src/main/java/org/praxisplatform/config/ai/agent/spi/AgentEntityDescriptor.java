package org.praxisplatform.config.ai.agent.spi;

import java.util.List;

/**
 * Descritor canonico de uma entidade/recurso descobrivel pelo assistente operacional.
 */
public record AgentEntityDescriptor(
        String entityKey,
        String resourceKey,
        String resourcePath,
        String title,
        String description,
        String icon,
        String idFieldName,
        String titleField,
        String subtitleField,
        List<String> metadataFields,
        String taxIdFilterField,
        String nameFilterField,
        String identifierFilterField,
        String routeTemplate,
        String primaryDomain,
        int matchPriority,
        String supersedesEntityKey,
        boolean includeInGlobalPolymorphicSearch
) {
    public AgentEntityDescriptor(
            String entityKey,
            String resourceKey,
            String resourcePath,
            String title,
            String description,
            String icon,
            String idFieldName,
            String titleField,
            String subtitleField,
            List<String> metadataFields,
            String taxIdFilterField,
            String nameFilterField,
            String identifierFilterField,
            String routeTemplate,
            String primaryDomain,
            int matchPriority) {
        this(
                entityKey,
                resourceKey,
                resourcePath,
                title,
                description,
                icon,
                idFieldName,
                titleField,
                subtitleField,
                metadataFields,
                taxIdFilterField,
                nameFilterField,
                identifierFilterField,
                routeTemplate,
                primaryDomain,
                matchPriority,
                null,
                true);
    }

    public AgentEntityDescriptor {
        metadataFields = metadataFields == null ? List.of() : List.copyOf(metadataFields);
    }
}
