package org.praxisplatform.config.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Value;

/**
 * Resposta serializada do endpoint de configuraÃ§Ã£o de UI.
 *
 * <p>
 * Espelha o registro resolvido para um componente, incluindo escopo efetivo, versÃ£o persistida,
 * {@code etag} atual e os blobs JSON de {@code payload} e {@code tags} jÃ¡ prontos para consumo
 * pelo host.
 * </p>
 */
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

