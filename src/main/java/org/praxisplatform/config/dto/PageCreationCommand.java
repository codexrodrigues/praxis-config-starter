package org.praxisplatform.config.dto;

import java.util.UUID;

/** P0 closed creation command. Scope, actor, document and publication state are server-owned. */
@io.swagger.v3.oas.annotations.media.Schema(
    description =
        "Closed P0 page creation command; actor, application, tenant, environment, output and"
            + " publication state are server-owned.",
    additionalProperties =
        io.swagger.v3.oas.annotations.media.Schema.AdditionalPropertiesValue.FALSE)
public record PageCreationCommand(UUID creationKey, PageSourceReference source, String title) {}
