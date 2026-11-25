package org.praxisplatform.config.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.praxisplatform.rules.facts.RuleFactCatalogDocument;
import org.praxisplatform.rules.facts.RuleFactContract;
import org.praxisplatform.rules.facts.RuleFactDescriptor;
import org.praxisplatform.rules.facts.RuleFactOperationCatalog;
import org.praxisplatform.rules.facts.RuleFactSchema;
import org.praxisplatform.uischema.authoring.DtoMetadataFactCatalogEnricher;
import org.praxisplatform.uischema.authoring.DtoMetadataFactCatalogEnricher.DtoFactPropertyMetadata;
import org.springframework.util.StringUtils;

/**
 * Implementação de {@link DtoRuleFactContractFactory} apoiada em {@link
 * DtoMetadataFactCatalogEnricher} do {@code praxis-metadata-starter}, com cache thread-safe para
 * introspecção de DTOs, compilação de {@link RuleFactContract} (perfil fechado G1 do {@link
 * RuleFactSchema}) e emissão de {@code factCatalog}.
 */
public class MetadataDtoRuleFactContractFactory implements DtoRuleFactContractFactory {

  private static final int MAX_OBJECT_PROPERTIES = 256;
  private static final int MAX_STRING_LENGTH = 64_000;
  private static final int MAX_ARRAY_ITEMS = 1_000;

  private final ObjectMapper objectMapper;
  private final DtoMetadataFactCatalogEnricher enricher;
  private final ConcurrentHashMap<Class<?>, Map<String, DtoFactPropertyMetadata>> dtoCache =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<ContractCacheKey, RuleFactContract> contractCache =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<CatalogCacheKey, ObjectNode> catalogCache =
      new ConcurrentHashMap<>();

  public MetadataDtoRuleFactContractFactory(ObjectMapper objectMapper) {
    this(objectMapper, new DtoMetadataFactCatalogEnricher());
  }

  public MetadataDtoRuleFactContractFactory(
      ObjectMapper objectMapper, DtoMetadataFactCatalogEnricher enricher) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
    this.enricher = Objects.requireNonNull(enricher, "enricher is required");
  }

  @Override
  public RuleFactContract buildOrGetContract(
      String hostContractVersion,
      String operationKey,
      String providerRef,
      Map<String, Class<?>> rootDtoBindings,
      List<String> declaredFactPaths,
      List<RuleFactContract.JavaInputs> javaExecutors,
      RuleFactCatalogDocument existingCatalog) {
    String normalizedHostVersion = requireText(hostContractVersion, "hostContractVersion");
    String normalizedOperation = requireText(operationKey, "operationKey");
    String normalizedProviderRef = requireText(providerRef, "providerRef");
    Map<String, Class<?>> safeRoots = normalizeRootBindings(rootDtoBindings);
    List<String> safePaths = normalizeFactPaths(declaredFactPaths);
    List<RuleFactContract.JavaInputs> safeExecutors =
        javaExecutors == null ? List.of() : List.copyOf(javaExecutors);

    ContractCacheKey cacheKey =
        new ContractCacheKey(
            normalizedHostVersion,
            normalizedOperation,
            normalizedProviderRef,
            bindingSignature(safeRoots),
            safePaths,
            safeExecutors,
            catalogFingerprint(existingCatalog));

    return contractCache.computeIfAbsent(
        cacheKey,
        ignored -> {
          ObjectNode schemaNode =
              buildClosedProfileSchema(
                  normalizedOperation, safeRoots, safePaths, existingCatalog);
          RuleFactSchema compiledSchema = RuleFactSchema.compile(schemaNode);
          return new RuleFactContract(
              normalizedHostVersion,
              normalizedOperation,
              normalizedProviderRef,
              compiledSchema,
              safeExecutors);
        });
  }

  @Override
  public Optional<ObjectNode> buildEnrichedFactCatalog(
      String familyKey,
      String serviceKey,
      String domainKey,
      String operationKey,
      List<String> factPaths,
      Map<String, Class<?>> rootDtoBindings,
      RuleFactCatalogDocument existingCatalog) {
    Map<String, Class<?>> safeRoots = normalizeRootBindings(rootDtoBindings);
    List<String> safePaths = normalizeFactPaths(factPaths);
    if (safeRoots.isEmpty() && existingCatalog == null && safePaths.isEmpty()) {
      return Optional.empty();
    }
    CatalogCacheKey key =
        new CatalogCacheKey(
            nullToEmpty(familyKey),
            nullToEmpty(serviceKey),
            nullToEmpty(domainKey),
            nullToEmpty(operationKey),
            bindingSignature(safeRoots),
            safePaths,
            catalogFingerprint(existingCatalog));

    String providerPrefix = resolveProviderPrefix(serviceKey, domainKey);
    ObjectNode cached =
        catalogCache.computeIfAbsent(
            key,
            ignored ->
                enricher.buildEnrichedFactCatalog(
                    objectMapper,
                    GovernedRulesStudioDefinitionSeeder.FACT_CATALOG_SCHEMA_VERSION,
                    familyKey,
                    operationKey,
                    providerPrefix,
                    safePaths,
                    safeRoots,
                    existingCatalog));
    return Optional.of(cached.deepCopy());
  }

  private static String resolveProviderPrefix(String serviceKey, String domainKey) {
    String cleanService = serviceKey == null ? "" : serviceKey.trim();
    String cleanDomain = domainKey == null ? "" : domainKey.trim();
    if (!cleanService.isEmpty() && !cleanDomain.isEmpty()) {
      return cleanService + ":" + cleanDomain;
    }
    if (!cleanService.isEmpty()) {
      return cleanService;
    }
    return !cleanDomain.isEmpty() ? cleanDomain : "host";
  }

  @Override
  public int cacheSize() {
    return contractCache.size();
  }

  @Override
  public void invalidateCache() {
    dtoCache.clear();
    contractCache.clear();
    catalogCache.clear();
  }

  private Map<String, DtoFactPropertyMetadata> inspectDtoCached(Class<?> dtoClass) {
    return dtoCache.computeIfAbsent(dtoClass, enricher::inspectDto);
  }

  private ObjectNode buildClosedProfileSchema(
      String operationKey,
      Map<String, Class<?>> rootBindings,
      List<String> declaredPaths,
      RuleFactCatalogDocument existingCatalog) {
    SchemaTreeNode root = SchemaTreeNode.objectNode();

    for (Map.Entry<String, Class<?>> entry : rootBindings.entrySet()) {
      SchemaTreeNode rootContainer = root.ensureChildObject(entry.getKey());
      Map<String, DtoFactPropertyMetadata> properties = inspectDtoCached(entry.getValue());
      for (Map.Entry<String, DtoFactPropertyMetadata> prop : properties.entrySet()) {
        if (rootContainer.children.size() >= MAX_OBJECT_PROPERTIES) {
          break;
        }
        DtoFactPropertyMetadata meta = prop.getValue();
        rootContainer.children.putIfAbsent(
            prop.getKey(), SchemaTreeNode.leaf(meta.valueType(), meta.nullable()));
      }
    }

    for (String path : declaredPaths) {
      List<String> segments = splitPathSegments(path);
      if (segments.isEmpty()) {
        continue;
      }
      LeafSpec resolvedSpec = resolveLeafSpec(path, operationKey, rootBindings, existingCatalog);
      SchemaTreeNode cursor = root;
      for (int i = 0; i < segments.size() - 1; i++) {
        String segment = segments.get(i);
        cursor.required.add(segment);
        cursor = cursor.ensureChildObject(segment);
      }
      String leafName = segments.getLast();
      cursor.required.add(leafName);
      cursor.children.compute(
          leafName,
          (ignored, current) -> {
            if (resolvedSpec != null) {
              return SchemaTreeNode.leaf(resolvedSpec.valueType(), resolvedSpec.nullable());
            }
            return current != null ? current : SchemaTreeNode.leaf("string", true);
          });
    }

    return toJsonSchema(root);
  }

  private LeafSpec resolveLeafSpec(
      String path,
      String operationKey,
      Map<String, Class<?>> rootBindings,
      RuleFactCatalogDocument existingCatalog) {
    if (existingCatalog != null) {
      Optional<RuleFactDescriptor> catalogFact =
          StringUtils.hasText(operationKey)
              ? existingCatalog.findFact(operationKey, path)
              : Optional.empty();
      if (catalogFact.isEmpty()) {
        catalogFact = existingCatalog.findFactAcrossOperations(path);
      }
      if (catalogFact.isPresent()) {
        RuleFactDescriptor descriptor = catalogFact.get();
        return new LeafSpec(descriptor.valueType(), descriptor.nullable());
      }
    }
    if (!rootBindings.isEmpty()) {
      Optional<DtoFactPropertyMetadata> resolved = enricher.resolveFactPath(path, rootBindings);
      if (resolved.isPresent()) {
        DtoFactPropertyMetadata meta = resolved.get();
        return new LeafSpec(meta.valueType(), meta.nullable());
      }
    }
    return null;
  }

  private ObjectNode toJsonSchema(SchemaTreeNode node) {
    ObjectNode out = objectMapper.createObjectNode();
    if (node.isObject) {
      out.put("type", "object");
      ObjectNode props = out.putObject("properties");
      ArrayNode req = out.putArray("required");
      Set<String> emittedKeys = new LinkedHashSet<>();

      // Emit required paths first so large DTOs preserve every governed binding path under the
      // closed-profile limit of 256 properties per object.
      for (String requiredKey : node.required) {
        SchemaTreeNode child = node.children.get(requiredKey);
        if (child != null && emittedKeys.size() < MAX_OBJECT_PROPERTIES) {
          emittedKeys.add(requiredKey);
          req.add(requiredKey);
          props.set(requiredKey, toJsonSchema(child));
        }
      }
      for (Map.Entry<String, SchemaTreeNode> entry : node.children.entrySet()) {
        if (emittedKeys.size() >= MAX_OBJECT_PROPERTIES) {
          break;
        }
        if (emittedKeys.add(entry.getKey())) {
          props.set(entry.getKey(), toJsonSchema(entry.getValue()));
        }
      }
      out.put("additionalProperties", false);
      out.put("maxProperties", Math.min(MAX_OBJECT_PROPERTIES, Math.max(1, props.size())));
      return out;
    }

    String normalizedType =
        node.valueType == null ? "string" : node.valueType.trim().toLowerCase(Locale.ROOT);
    return switch (normalizedType) {
      case "boolean" -> scalarTypeNode("boolean", node.nullable);
      case "integer" -> scalarTypeNode("integer", node.nullable);
      case "number" -> scalarTypeNode("number", node.nullable);
      case "string-array", "date-array" -> arrayTypeNode("string", node.nullable);
      case "number-array", "array<number>" -> arrayTypeNode("number", node.nullable);
      case "date", "string" -> stringTypeNode(node.nullable);
      default -> stringTypeNode(node.nullable);
    };
  }

  private ObjectNode scalarTypeNode(String jsonType, boolean nullable) {
    ObjectNode node = objectMapper.createObjectNode();
    putType(node, jsonType, nullable);
    return node;
  }

  private ObjectNode stringTypeNode(boolean nullable) {
    ObjectNode node = objectMapper.createObjectNode();
    putType(node, "string", nullable);
    node.put("maxLength", MAX_STRING_LENGTH);
    return node;
  }

  private ObjectNode arrayTypeNode(String elementJsonType, boolean nullable) {
    ObjectNode node = objectMapper.createObjectNode();
    putType(node, "array", nullable);
    node.put("minItems", 0);
    node.put("maxItems", MAX_ARRAY_ITEMS);
    ObjectNode items = objectMapper.createObjectNode();
    items.put("type", elementJsonType);
    if ("string".equals(elementJsonType)) {
      items.put("maxLength", MAX_STRING_LENGTH);
    }
    node.set("items", items);
    return node;
  }

  private static void putType(ObjectNode node, String jsonType, boolean nullable) {
    if (nullable) {
      ArrayNode types = node.putArray("type");
      types.add(jsonType);
      types.add("null");
    } else {
      node.put("type", jsonType);
    }
  }

  private static Map<String, Class<?>> normalizeRootBindings(Map<String, Class<?>> raw) {
    if (raw == null || raw.isEmpty()) {
      return Map.of();
    }
    Map<String, Class<?>> sorted = new TreeMap<>();
    raw.forEach(
        (key, type) -> {
          if (StringUtils.hasText(key) && type != null) {
            sorted.put(key.trim(), type);
          }
        });
    return Map.copyOf(sorted);
  }

  private static List<String> normalizeFactPaths(List<String> raw) {
    if (raw == null || raw.isEmpty()) {
      return List.of();
    }
    return raw.stream()
        .filter(StringUtils::hasText)
        .map(String::trim)
        .distinct()
        .sorted(Comparator.naturalOrder())
        .toList();
  }

  private static Map<String, String> bindingSignature(Map<String, Class<?>> bindings) {
    if (bindings.isEmpty()) {
      return Map.of();
    }
    Map<String, String> sig = new LinkedHashMap<>();
    bindings.forEach((key, type) -> sig.put(key, type.getName()));
    return Map.copyOf(sig);
  }

  private static List<String> splitPathSegments(String rawPath) {
    if (!StringUtils.hasText(rawPath)) {
      return List.of();
    }
    String normalized = rawPath.trim().replace(":items", "").replace(":any", "").replace("[]", "");
    if (normalized.startsWith("$.")) {
      normalized = normalized.substring(2);
    } else if (normalized.startsWith("/")) {
      normalized = normalized.substring(1).replace('/', '.');
    }
    String[] parts = normalized.split("\\.");
    List<String> out = new ArrayList<>(parts.length);
    for (String part : parts) {
      if (StringUtils.hasText(part)) {
        out.add(part.trim());
      }
    }
    return List.copyOf(out);
  }

  private static String catalogFingerprint(RuleFactCatalogDocument catalog) {
    if (catalog == null) {
      return "";
    }
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      updateHash(md, catalog.schemaVersion());
      if (catalog.operations() != null && !catalog.operations().isEmpty()) {
        List<String> opKeys = new ArrayList<>(catalog.operations().keySet());
        opKeys.sort(Comparator.naturalOrder());
        for (String opKey : opKeys) {
          updateHash(md, opKey);
          RuleFactOperationCatalog op = catalog.operations().get(opKey);
          if (op != null) {
            if (op.schema() != null) {
              updateHash(md, op.schema().digest());
            }
            if (op.facts() != null) {
              for (RuleFactDescriptor fact : op.facts()) {
                if (fact != null) {
                  updateHash(md, fact.schemaPointer());
                  updateHash(md, fact.factPath());
                  updateHash(md, fact.valueType());
                  updateHash(md, Boolean.toString(fact.nullable()));
                  updateHash(md, String.valueOf(fact.labels()));
                  updateHash(md, String.valueOf(fact.descriptions()));
                  updateHash(md, fact.providerRef());
                  updateHash(md, String.valueOf(fact.evidenceRefs()));
                  updateHash(md, fact.sensitivity());
                  updateHash(md, fact.redaction());
                }
              }
            }
          }
        }
      }
      return HexFormat.of().formatHex(md.digest());
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA-256 unavailable", ex);
    }
  }

  private static void updateHash(MessageDigest md, String value) {
    byte[] bytes = (value == null ? "<null>" : value).getBytes(StandardCharsets.UTF_8);
    md.update(bytes);
    md.update((byte) '\0');
  }

  private static String requireText(String value, String name) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value.trim();
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value.trim();
  }

  private record LeafSpec(String valueType, boolean nullable) {}

  private record ContractCacheKey(
      String hostContractVersion,
      String operationKey,
      String providerRef,
      Map<String, String> rootClassBindings,
      List<String> declaredFactPaths,
      List<RuleFactContract.JavaInputs> javaExecutors,
      String catalogFingerprint) {}

  private record CatalogCacheKey(
      String familyKey,
      String serviceKey,
      String domainKey,
      String operationKey,
      Map<String, String> rootClassBindings,
      List<String> factPaths,
      String catalogFingerprint) {}

  private static final class SchemaTreeNode {
    final boolean isObject;
    final String valueType;
    final boolean nullable;
    final Map<String, SchemaTreeNode> children;
    final Set<String> required;

    private SchemaTreeNode(boolean isObject, String valueType, boolean nullable) {
      this.isObject = isObject;
      this.valueType = valueType;
      this.nullable = nullable;
      this.children = isObject ? new LinkedHashMap<>() : Map.of();
      this.required = isObject ? new LinkedHashSet<>() : Set.of();
    }

    static SchemaTreeNode objectNode() {
      return new SchemaTreeNode(true, "object", false);
    }

    static SchemaTreeNode leaf(String valueType, boolean nullable) {
      return new SchemaTreeNode(false, valueType, nullable);
    }

    SchemaTreeNode ensureChildObject(String name) {
      SchemaTreeNode existing = children.get(name);
      if (existing != null && existing.isObject) {
        return existing;
      }
      SchemaTreeNode created = objectNode();
      children.put(name, created);
      return created;
    }
  }
}
