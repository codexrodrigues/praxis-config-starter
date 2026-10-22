package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.praxisplatform.config.dto.PageCreationCommand;

/** Closed P0 wire input and identity-preserving private evidence serialization. */
final class PageCreationCodec {
  static final int MAX_EVIDENCE_BYTES = 2 * 1024 * 1024;
  private final ObjectMapper mapper = UiLayoutPageChildCaptures.evidenceMapper();
  private final JsonSchema command;
  PageCreationCodec() {
    try(var stream=getClass().getResourceAsStream("/org/praxisplatform/config/page-publication.schema.json")) {
      ObjectNode schema=(ObjectNode)mapper.readTree(stream); schema.put("$ref","#/$defs/createCommand");
      command=JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema);
    } catch(Exception e) { throw new IllegalStateException("Page creation schema unavailable."); }
  }
  PageCreationCommand command(String raw) {
    try {
      JsonNode node=UiLayoutRevisionJsonInput.readDocument(raw,()->{});
      if(!command.validate(node).isEmpty()) throw invalid();
      return mapper.treeToValue(node,PageCreationCommand.class);
    } catch(Exception e) { throw invalid(); }
  }
  void require(PageCreationCommand value) { command(write(value)); }
  String write(Object value) {
    try { String text=mapper.writeValueAsString(value); bounded(text); return text; }
    catch(Exception e) { throw invalid(); }
  }
  PageCreationEvidence read(String text) {
    try { bounded(text); return mapper.readValue(text,PageCreationEvidence.class); }
    catch(Exception e) { throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.INVALID_STATE,"Page creation evidence is invalid."); }
  }
  JsonNode tree(Object value) { return mapper.valueToTree(value); }
  private void bounded(String text) {
    if(text==null || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_EVIDENCE_BYTES) throw invalid();
  }
  private static UiLayoutLifecycleException invalid() {
    return new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.INVALID_REQUEST,"Page creation command or evidence is invalid.");
  }
}
