package org.praxisplatform.config.ai.authoring;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(
        name = "AgenticAuthoringComponentCapabilitiesResult",
        description = "Catálogo de operações de authoring que o backend pode considerar ao compor ou editar componentes, acompanhado da proveniência operacional da leitura governada.")
public record AgenticAuthoringComponentCapabilitiesResult(
        @Schema(description = "Versão do formato do catálogo devolvido pelo serviço de authoring.", example = "0.1.0")
        String version,
        @Schema(description = "Capacidades agrupadas pelo identificador canônico do componente que as declara.")
        List<ComponentCapabilityCatalog> catalogs,
        @Schema(description = "Diagnóstico que permite distinguir uma leitura atual do AI Registry de uma resposta degradada.")
        ComponentCapabilityDiagnostics diagnostics) {

    public AgenticAuthoringComponentCapabilitiesResult(
            String version,
            List<ComponentCapabilityCatalog> catalogs) {
        this(version, catalogs, null);
    }

    @Schema(
            name = "AgenticAuthoringComponentCapabilityDiagnostics",
            description = "Proveniência e estado de atualização usados para avaliar se o catálogo representa a revisão governada corrente ou uma contingência observável.")
    public record ComponentCapabilityDiagnostics(
            @Schema(
                    description = "Origem efetiva do catálogo: registry, last-known-good, snapshot-fallback ou snapshot.",
                    allowableValues = {"registry", "last-known-good", "snapshot-fallback", "snapshot"})
            String source,
            @Schema(description = "Indica que a revisão corrente do AI Registry não pôde ser materializada nesta leitura.")
            boolean degraded,
            @Schema(description = "Código estável da causa de degradação; ausente quando a leitura não está degradada.", nullable = true)
            String degradationReason,
            @Schema(description = "Instante em que a origem e o estado publicados foram avaliados.", format = "date-time")
            Instant resolvedAt,
            @Schema(description = "Instante da última leitura bem-sucedida do catálogo governado nesta instância; ausente antes do primeiro sucesso.", format = "date-time", nullable = true)
            Instant lastSuccessfulRegistryLoadAt) {
    }

    @Schema(
            name = "AgenticAuthoringComponentCapabilityCatalog",
            description = "Conjunto de operações que podem orientar authoring para um componente canônico.")
    public record ComponentCapabilityCatalog(
            @Schema(description = "Identificador canônico do componente proprietário das operações.")
            String componentId,
            @Schema(description = "Versão do manifesto governado que originou as operações do componente.")
            String version,
            @Schema(description = "Operações e exemplos semânticos disponíveis para o componente.")
            List<ComponentCapability> capabilities) {
    }

    @Schema(
            name = "AgenticAuthoringComponentCapability",
            description = "Operação declarada que fornece evidência semântica para planejamento e materialização governada.")
    public record ComponentCapability(
            @Schema(description = "Identificador estável da operação declarada pelo catálogo ou manifesto.")
            String id,
            @Schema(description = "Tipo de alteração semântica representada pela operação.")
            String changeKind,
            @Schema(description = "Termos do próprio contrato usados como evidência após a resolução do escopo semântico.")
            List<String> triggerTerms,
            @Schema(description = "Relações declaradas entre campos canônicos e nomes apresentados ao usuário.")
            List<ComponentFieldAlias> fieldAliases,
            @Schema(description = "Exemplos positivos do manifesto que ajudam a explicar a finalidade da operação.")
            List<ComponentCapabilityExample> examples) {
    }

    @Schema(
            name = "AgenticAuthoringComponentCapabilityExample",
            description = "Exemplo governado que relaciona uma solicitação humana à intenção e às restrições de uma operação declarada.")
    public record ComponentCapabilityExample(
            @Schema(description = "Solicitação ilustrativa publicada pelo dono do componente.")
            String prompt,
            @Schema(description = "Efeito semântico que o exemplo pretende materializar.")
            String intent,
            @Schema(description = "Restrições e caminhos do contrato associados ao exemplo.")
            List<String> configHints) {
    }

    @Schema(
            name = "AgenticAuthoringComponentFieldAlias",
            description = "Vocabulário de apresentação associado a um campo canônico do componente.")
    public record ComponentFieldAlias(
            @Schema(description = "Nome canônico do campo no contrato do componente.")
            String field,
            @Schema(description = "Nomes de apresentação declarados para o campo.")
            List<String> aliases) {
    }
}
