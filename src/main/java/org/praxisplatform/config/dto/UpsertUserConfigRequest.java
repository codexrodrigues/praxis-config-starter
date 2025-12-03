package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpsertUserConfigRequest {

  @NotNull private JsonNode payload;

  private JsonNode tags;
}
