package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.praxisplatform.config.dto.PageSourceReference;
import org.praxisplatform.config.dto.UiLayoutTarget;

/**
 * SPI de bean único para hosts que publicam páginas dinâmicas corporativas e grupos de navegação
 * governados sobre {@code praxis-config-starter}.
 *
 * <p>Ao registrar um único bean {@code CorporatePageCatalogContributor}, a auto-configuração
 * {@code UiLayoutCorporatePagesAutoConfiguration} instancia automaticamente defaults coesos e
 * fail-closed para os 10 contratos SPI do ciclo de vida de páginas e layout ({@link PageCreationHost},
 * {@link PageLifecycleAdmission}, {@link UiLayoutLifecycleInvocationProvider},
 * {@link EffectiveUiAudienceProvider}, {@link EnterpriseRuntimeNavigationProvider},
 * {@link UiLayoutDraftWorkspaceSource}, {@link UiLayoutBaselineMetadataAdmission},
 * {@link UiLayoutValidationBudgetPolicy}, {@link UiLayoutReleaseValidator} e
 * {@link UiLayoutLifecycleStructureValidator}), preservando substituição individual por
 * {@code @ConditionalOnMissingBean}.
 */
public interface CorporatePageCatalogContributor {

    /** Identificador estável da aplicação proprietária das páginas. */
    String applicationId();

    /** Namespace base das rotas publicadas de página (ex.: {@code /pages}). */
    default String routeNamespace() {
        return "/pages";
    }

    /** Identificador lógico de serviço publicado na procedência B0 de documentos nativos. */
    default String serviceKey() {
        return applicationId();
    }

    /** Orçamento máximo padrão de validação por operação de ciclo de vida. */
    default Duration validationBudget() {
        return Duration.ofSeconds(30);
    }

    /**
     * Resolve o UUID estável do root {@code praxis-app-shell} (grupos de navegação) para o par
     * {@code (tenantId, environment)}.
     *
     * <p>O mapeamento padrão é determinístico e isolado por aplicação, tenant, ambiente e
     * {@link #routeNamespace()}.
     */
    default UUID navigationGroupsRootId(String tenantId, String environment) {
        String key = applicationId()
                + "|"
                + tenantId
                + "|"
                + environment
                + "|"
                + routeNamespace()
                + "|navigation-groups";
        return UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Grupos iniciais de navegação emitidos como baseline para {@code praxis-app-shell}. */
    default List<NavigationGroupSeed> navigationGroups() {
        return List.of();
    }

    /** Catálogo de páginas nativas registradas pelo host para captura B0/copiabilidade. */
    default List<NativePageSeed> nativePages() {
        return List.of();
    }

    /** Ícones adicionais permitidos além do catálogo canônico Material Icons admitido pela plataforma. */
    default Set<String> additionalAllowedIcons() {
        return Set.of();
    }

    /** Quando verdadeiro, aceita qualquer ícone Material válido em {@code snake_case} sem allowlist fechada. */
    default boolean allowAnyMaterialIcon() {
        return false;
    }

    /**
     * Hook opcional para resolver identidade corporativa ({@link ResolvedActorContext}) a partir
     * do {@link Principal} autenticado. Quando vazio, a plataforma resolve o contexto padrão.
     */
    default Optional<ResolvedActorContext> resolveActor(Principal principal, String contextVersion) {
        return Optional.empty();
    }

    /** Hook opcional executado dentro da janela de admissão de ciclo de vida do host. */
    default void checkLifecycleOperation(
            UiLayoutLifecycleOperation operation, UiLayoutLifecycleInvocation invocation) {}

    /** Hook opcional executado na admissão conjunta de criação/cópia de páginas. */
    default void checkPageCreation(
            PageCreationOperation operation,
            PageCreationContext context,
            PageSourceReference source) {}

    /**
     * Tipos de widget admitidos para captura dinâmica composta via
     * {@code composed-widget:<widgetType>} em revisões multi-widget. Por padrão, vazio (fail-closed).
     */
    default Set<String> admittedComposedWidgetTypes() {
        return Set.of();
    }

    /** Hook opcional executado na admissão de um widget filho composto em revisão/verificação. */
    default void checkComposedWidgetAdmission(
            UiLayoutLifecycleInvocation invocation,
            UiLayoutTarget page,
            String widgetType,
            JsonNode childDocument) {}

    /** Contexto autenticado e delimitado do ator para resolução e ciclo de vida de páginas. */
    record ResolvedActorContext(
            String actorRef,
            String tenantId,
            String administrativeUnit,
            String environment,
            String organization,
            String sector,
            String profile,
            Set<String> groups,
            String contextVersion) {}

    /** Definição declarativa de grupo de navegação do menu corporativo. */
    record NavigationGroupSeed(UUID id, String label, String icon, int order) {}

    /** Semente nativa de página registrada pelo host. */
    record NativePageSeed(
            String sourceId,
            String revision,
            UUID pageId,
            JsonNode document,
            String title,
            String icon) {}
}
