package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class UserConfigResponse {
  String componentType;
  String componentId;
  String environment;
  String scope;
  long version;
  String etag;
  JsonNode payload;
  JsonNode tags;
}
