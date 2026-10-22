package org.praxisplatform.config.dto;

/** Resolution request only. Clients cannot supply source content, admission or capture evidence. */
public record UiLayoutPageChildSource(String widgetKey, NativeSource source) {
  public UiLayoutPageChildSource {
    if (widgetKey == null || !widgetKey.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") || source == null) throw invalid();
  }
  public record NativeSource(String kind, String sourceId, String revision) {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final com.networknt.schema.JsonSchema SCHEMA = schema();
    public NativeSource {
      var value = JSON.createObjectNode().put("kind", kind).put("sourceId", sourceId).put("revision", revision);
      if (!SCHEMA.validate(value).isEmpty()) throw invalid();
    }
    private static com.networknt.schema.JsonSchema schema() {
      try (var input = UiLayoutPageChildSource.class.getResourceAsStream("/org/praxisplatform/config/page-publication.schema.json")) {
        var schema = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(input);
        schema.put("$ref", "#/$defs/nativeSource");
        return com.networknt.schema.JsonSchemaFactory.getInstance(com.networknt.schema.SpecVersion.VersionFlag.V202012).getSchema(schema);
      } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
  }
  private static IllegalArgumentException invalid() { return new IllegalArgumentException("Page child source reference is invalid."); }
}
