package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Actor responsible for deliberately reactivating a previously published snapshot. */
@Schema(description = "Governed audit identity for an explicit rollback activation.")
public record DomainRuleSnapshotRollbackRequest(
    @Schema(description = "Safe actor reference accountable for selecting the prior immutable snapshot.", example = "on-call-operator", requiredMode = Schema.RequiredMode.REQUIRED)
    String activatedBy) {}
