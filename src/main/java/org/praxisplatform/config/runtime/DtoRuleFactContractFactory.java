package org.praxisplatform.config.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.praxisplatform.rules.facts.RuleFactCatalogDocument;
import org.praxisplatform.rules.facts.RuleFactContract;

/**
 * Fábrica desacoplada para geração dinâmica, enriquecida por metadados e em cache de {@link
 * RuleFactContract}s e {@code factCatalog}s a partir de bindings de DTOs de host e caminhos de
 * fatos declarados.
 *
 * <p>Permite que auto-configurações e seeders de {@code praxis-config-starter} operem sem
 * dependência obrigatória de {@code praxis-metadata-starter} no classpath, delegando a introspecção
 * semântica de DTOs quando o módulo opcional estiver disponível.
 */
public interface DtoRuleFactContractFactory {

  /**
   * Constrói ou recupera do cache um {@link RuleFactContract} estruturalmente compilado a partir
   * dos DTOs associados às raízes de fatos, dos caminhos declarados e de um catálogo pré-curado
   * opcional.
   *
   * @param hostContractVersion versão do contrato do host (ex.: {@code "host/1.0"})
   * @param operationKey chave canônica da operação executada
   * @param providerRef referência versionada do provedor de fatos
   * @param rootDtoBindings mapa de raiz lógica (ex.: {@code "draft"}, {@code "context"}) para
   *     classe DTO do host
   * @param declaredFactPaths caminhos de fatos declarados em bindings/templates/expressões
   * @param javaExecutors contratos explícitos de entradas Java para a operação
   * @param existingCatalog catálogo ontológico pré-curado opcional para precedência tipada
   * @return contrato compilado pronto para validação e execução fail-closed
   */
  RuleFactContract buildOrGetContract(
      String hostContractVersion,
      String operationKey,
      String providerRef,
      Map<String, Class<?>> rootDtoBindings,
      List<String> declaredFactPaths,
      List<RuleFactContract.JavaInputs> javaExecutors,
      RuleFactCatalogDocument existingCatalog);

  /**
   * Sobrecarga conveniente sem catálogo pré-curado explícito.
   */
  default RuleFactContract buildOrGetContract(
      String hostContractVersion,
      String operationKey,
      String providerRef,
      Map<String, Class<?>> rootDtoBindings,
      List<String> declaredFactPaths,
      List<RuleFactContract.JavaInputs> javaExecutors) {
    return buildOrGetContract(
        hostContractVersion,
        operationKey,
        providerRef,
        rootDtoBindings,
        declaredFactPaths,
        javaExecutors,
        null);
  }

  /**
   * Sobrecarga conveniente sem executores Java nem catálogo pré-curado.
   */
  default RuleFactContract buildOrGetContract(
      String hostContractVersion,
      String operationKey,
      String providerRef,
      Map<String, Class<?>> rootDtoBindings,
      List<String> declaredFactPaths) {
    return buildOrGetContract(
        hostContractVersion,
        operationKey,
        providerRef,
        rootDtoBindings,
        declaredFactPaths,
        List.of(),
        null);
  }

  /**
   * Constrói ou recupera do cache o documento JSON {@code factCatalog} enriquecido por DTOs e
   * governança de dados para publicação no catálogo de definições do Policy Studio.
   *
   * @param familyKey chave canônica da família de regras
   * @param serviceKey chave do serviço proprietário
   * @param domainKey chave do domínio funcional
   * @param operationKey chave da operação
   * @param factPaths caminhos de fatos relevantes
   * @param rootDtoBindings bindings de raízes para classes DTO
   * @param existingCatalog catálogo pré-curado opcional
   * @return {@code factCatalog} enriquecido quando suportado pela implementação
   */
  Optional<ObjectNode> buildEnrichedFactCatalog(
      String familyKey,
      String serviceKey,
      String domainKey,
      String operationKey,
      List<String> factPaths,
      Map<String, Class<?>> rootDtoBindings,
      RuleFactCatalogDocument existingCatalog);

  /** Quantidade atual de contratos compilados mantidos em cache em memória. */
  int cacheSize();

  /** Limpa os caches em memória de contratos, catálogos e introspecção de DTOs. */
  void invalidateCache();
}
