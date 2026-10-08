package org.praxisplatform.config.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.TransactionStatus;

/** Synthetic unit fixtures only; not a real producer, persisted database or runtime proof. */
final class UiLayoutMetadataTestFixtures {
  static UiLayoutBaselineMetadataSeed.DocumentAssembly assembly() {
    return new UiLayoutBaselineMetadataSeed.DocumentAssembly(1, "fixture-full-document-assembler",
        " {\"config\":{\"columns\":[]},\"bindings\":{},\"context\":{}} ");
  }
  static UiLayoutBaselineMetadataSeed metadata() { return metadata("actor", "unit", "ctx"); }
  static UiLayoutBaselineMetadataSeed metadata(String actor, String unit, String context) {
    return new UiLayoutBaselineMetadataSeed(
        new UiLayoutBaselineMetadataSeed.OperationSource("fixture-service", "fixture-resource", "/fixture/resources", "GET",
            "response", "urn:fixture:schema", "fixture-producer", "fixture-publication"),
        new UiLayoutBaselineMetadataSeed.SchemaProjection("fixture-normalizer", "fixture-projector", assembly()),
        new UiLayoutBaselineMetadataSeed.Observation(actor, unit, context, "fixture-policy", "fixture-revision",
            Instant.parse("2026-10-02T12:00:00.123456789Z")),
        " {\"properties\":{\"b\":{},\"a\":{}}} ");
  }

  static UiLayoutBaselineMetadataSeed nativeMetadata(com.fasterxml.jackson.databind.JsonNode document,
      String actor, String unit, String context) {
    return new UiLayoutBaselineMetadataSeed(
        new UiLayoutBaselineMetadataSeed.NativeDocumentSource("fixture-service", "fixture-document", "revision-1",
            "fixture-producer", "fixture-publication"),
        new UiLayoutBaselineMetadataSeed.NativeIdentity(), metadata(actor, unit, context).observation(), document.toString());
  }

  static class MemoryStore implements UiLayoutMetadataCapturePersistence {
    final Map<UiLayoutMetadataCapture.Binding, UiLayoutMetadataCapture> rows = new LinkedHashMap<>();
    int appends;
    int reads;
    @Override public void append(TransactionStatus status, UiLayoutLifecycleInvocation invocation, UUID draft,
        List<UiLayoutMetadataCapture> captures, UiLayoutMetadataCaptureAccess access) {
      appends++;
      for (var capture : captures) {
        access.require(invocation, capture);
        if (rows.putIfAbsent(capture.binding(), capture) != null) {
          throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.INVALID_STATE, "Duplicate fixture capture.");
        }
      }
    }
    @Override public UiLayoutMetadataCapture read(UiLayoutLifecycleInvocation invocation,
        UiLayoutMetadataCapture.Binding expected, UiLayoutMetadataCaptureAccess access) {
      reads++;
      var value = rows.get(expected);
      if (value == null) throw new UiLayoutLifecycleException(UiLayoutLifecycleException.Code.INVALID_STATE, "Missing fixture capture.");
      access.require(invocation, value);
      return value;
    }
  }
}
