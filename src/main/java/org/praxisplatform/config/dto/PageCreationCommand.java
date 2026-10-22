package org.praxisplatform.config.dto;

import java.util.UUID;

/** P0 closed creation command. Scope, actor, document and publication state are server-owned. */
public record PageCreationCommand(UUID creationKey, PageSourceReference source, String title) {}
