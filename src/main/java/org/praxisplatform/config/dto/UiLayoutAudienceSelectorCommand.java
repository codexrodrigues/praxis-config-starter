package org.praxisplatform.config.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** Closed audience selector command; tenant is checked against the host-confirmed lifecycle scope. */
@Schema(description = "Closed layout audience selector. No caller-provided selector can expand the host-confirmed tenant scope.")
public record UiLayoutAudienceSelectorCommand(
    @Schema(description = "Tenant that must equal the server-confirmed lifecycle tenant.", requiredMode = Schema.RequiredMode.REQUIRED) String tenant,
    @Schema(description = "Optional server-authorized organization selector.", nullable = true) String organization,
    @Schema(description = "Optional server-authorized sector selector.", nullable = true) String sector,
    @Schema(description = "Optional server-authorized group selector.", nullable = true) String group,
    @Schema(description = "Optional server-authorized profile selector.", nullable = true) String profile,
    @Schema(description = "Optional server-authorized user selector.", nullable = true) String user) {}
