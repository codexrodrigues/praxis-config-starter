package org.praxisplatform.config.service;

/** Host-attested identity of one native authoring document contract. */
public record UiLayoutAuthoringDocumentDescriptor(
    String documentType,
    String schemaRef,
    String schemaVersion) {
  public UiLayoutAuthoringDocumentDescriptor {
    documentType = required(documentType, "documentType", 255);
    schemaRef = required(schemaRef, "schemaRef", 1024);
    schemaVersion = required(schemaVersion, "schemaVersion", 128);
  }

  private static String required(String value, String name, int maximumLength) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required.");
    String normalized = value.trim();
    if (normalized.length() > maximumLength) throw new IllegalArgumentException(name + " is too long.");
    return normalized;
  }
}
