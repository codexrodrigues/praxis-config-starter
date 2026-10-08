package org.praxisplatform.config.service;

import java.time.Instant;

/**
 * Server-only correlated origin evidence accompanying a native B0 seed.
 * Declared identities are not attestation. Never deserialize this SPI from HTTP or expose raw content in receipts.
 * @param rawInputText unmodified JSON input used by the producer to derive this native B0;
 *        strict UTF-8, object syntax, duplicate-key, depth and byte gates run in the Config codec
 */
public record UiLayoutBaselineMetadataSeed(Source source, Reproduction reproduction, Observation observation,
    String rawInputText) {
  public UiLayoutBaselineMetadataSeed {
    if (source == null || reproduction == null || observation == null || rawInputText == null
        || rawInputText.length() > UiLayoutMetadataCaptureCodec.MAX_BODY_BYTES) throw invalid();
    if (source instanceof OperationSource && !(reproduction instanceof SchemaProjection)
        || source instanceof NativeDocumentSource && !(reproduction instanceof NativeIdentity)) throw invalid();
  }
  @Override public String toString() { return "UiLayoutBaselineMetadataSeed[content redacted]"; }
  /** Closed admitted origin forms; every target requires its own genuine source. */
  public sealed interface Source permits OperationSource, NativeDocumentSource {
    String serviceKey();
    String producerRef();
    String publicationRef();
  }

  /**
   * Declared structural operation/publication; host admission must independently verify authority.
   * @param serviceKey exact governed service identity that produced the structural input
   * @param resourceRef exact resource identity admitted for this component; never derived from labels
   * @param path declared API operation path, including its canonical path parameters
   * @param method declared uppercase HTTP operation, not inferred from the path
   * @param schemaType request or response schema selected for that operation
   * @param schemaRef exact structural schema identity, distinct from the native editor document schema
   * @param producerRef admitted producer artifact identity responsible for obtaining the input and deriving B0
   * @param publicationRef immutable schema publication identity observed during that derivation
   */
  public record OperationSource(String serviceKey, String resourceRef, String path, String method, String schemaType,
      String schemaRef, String producerRef, String publicationRef) implements Source {
    public OperationSource {
      serviceKey = required(serviceKey, 255); resourceRef = required(resourceRef, 1024);
      path = required(path, 1024); method = required(method, 32);
      schemaType = required(schemaType, 32); schemaRef = required(schemaRef, 1024);
      producerRef = required(producerRef, 1024); publicationRef = required(publicationRef, 1024);
      if (!path.startsWith("/") || !method.matches("[A-Z][A-Z-]*")
          || !(schemaType.equals("request") || schemaType.equals("response"))) throw invalid();
    }
    @Override public String toString() { return "Source[redacted]"; }
  }

  /**
   * Versioned native document origin; it does not declare an HTTP operation or exempt evidence admission.
   * @param serviceKey governed authority that publishes the native document
   * @param documentRef exact source document identity; unrelated to a child's resource identity
   * @param documentRevision immutable revision of that source document
   * @param producerRef admitted artifact that observed the input and produced B0
   * @param publicationRef immutable publication identity of the observed native input
   */
  public record NativeDocumentSource(String serviceKey, String documentRef, String documentRevision,
      String producerRef, String publicationRef) implements Source {
    public NativeDocumentSource {
      serviceKey = required(serviceKey, 255); documentRef = required(documentRef, 1024);
      documentRevision = required(documentRevision, 255); producerRef = required(producerRef, 1024);
      publicationRef = required(publicationRef, 1024);
    }
    @Override public String toString() { return "NativeDocumentSource[redacted]"; }
  }

  /** Closed reproduction modes; missing or transformed native origins cannot select a fabricated pipeline. */
  public sealed interface Reproduction permits SchemaProjection, NativeIdentity {}

  /**
   * @param normalizerRef immutable artifact identity of the structural normalization used to derive B0
   * @param projectorRef immutable artifact identity of the component projection used after normalization
   * @param assembly exact variable inputs and full assembly-chain identity required to reproduce the native document
   * These declared refs require host admission; an editor schema version does not prove compatibility.
   */
  public record SchemaProjection(String normalizerRef, String projectorRef, DocumentAssembly assembly) implements Reproduction {
    public SchemaProjection {
      normalizerRef = required(normalizerRef, 1024); projectorRef = required(projectorRef, 1024);
      if (assembly == null) throw invalid();
    }
    @Override public String toString() { return "Reproduction[redacted]"; }
  }

  /**
   * Exact variable inputs for full native document construction, separately from raw structural schema.
   * The owner of the admitted assembler defines the JSON format and all fixed defaults/dependencies.
   * Config validates syntax and integrity, never executes or attests this input.
   * @param inputVersion supported capture envelope version (1); not the editor document version
   * @param assemblerRef immutable identity of the admitted full assembly chain, including its dependencies/defaults
   * @param inputText exact JSON object containing all variable config/bindings/context inputs used by that assembler
   */
  public record DocumentAssembly(int inputVersion, String assemblerRef, String inputText) {
    public DocumentAssembly {
      if (inputVersion != 1 || inputText == null || inputText.length() > UiLayoutMetadataCaptureCodec.MAX_BODY_BYTES) throw invalid();
      assemblerRef = required(assemblerRef, 1024);
    }
    @Override public String toString() { return "DocumentAssembly[content redacted]"; }
  }

  /** Native input is B0 itself, with object/array order preserved; codec verifies identity, host admits origin. */
  public record NativeIdentity() implements Reproduction {}

  /**
   * Historical observation context, never a replacement for current permission checks.
   * @param actorRef authenticated actor under whom the original input was observed
   * @param administrativeUnit exact effective administrative scope of that observation
   * @param contextVersion host context version correlated with actor/unit/composition at observation
   * @param policyRef effective policy identity that admitted the input/content for this target
   * @param policyRevision immutable revision of the policy observed then; not today's grant
   * @param capturedAt declared observation instant, preserving nanoseconds; not an attested commit clock
   */
  public record Observation(String actorRef, String administrativeUnit, String contextVersion,
      String policyRef, String policyRevision, Instant capturedAt) {
    public Observation {
      actorRef = required(actorRef, 255); administrativeUnit = required(administrativeUnit, 255);
      contextVersion = required(contextVersion, 255); policyRef = required(policyRef, 1024);
      policyRevision = required(policyRevision, 255);
      if (capturedAt == null) throw invalid();
    }
    @Override public String toString() { return "Observation[redacted]"; }
  }

  private static String required(String value, int maximum) {
    if (value == null || value.isBlank() || value.length() > maximum) throw invalid();
    // Identifiers are exact; do not silently normalize an invalid stored binding.
    if (!value.equals(value.trim()) || value.chars().anyMatch(Character::isISOControl)) throw invalid();
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (Character.isHighSurrogate(character)) {
        if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) throw invalid();
      } else if (Character.isLowSurrogate(character)) throw invalid();
    }
    return value;
  }
  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Metadata seed identity is invalid.");
  }
}
