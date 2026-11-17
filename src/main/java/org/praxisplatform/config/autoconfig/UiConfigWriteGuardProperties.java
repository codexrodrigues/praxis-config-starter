package org.praxisplatform.config.autoconfig;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurações da guarda de autorização e privacidade de escrita em {@code ui_user_config}.
 */
@ConfigurationProperties(prefix = "praxis.config.ui-write-guard")
public class UiConfigWriteGuardProperties {

    /**
     * Quando verdadeiro, bloqueia com {@code 422 SHARED_CONFIG_SENSITIVE_CRITERIA_DENIED} a escrita
     * em escopo {@code tenant} contendo critérios ou valores sobre campos pessoais, sensíveis,
     * financeiros ou restritos.
     */
    private boolean blockSensitiveTenantCriteria = true;

    /**
     * Pacotes base escaneados para descoberta de campos {@code @DomainGovernance} / {@code @Filterable}
     * em DTOs do host (quando {@code praxis-metadata-starter} estiver presente).
     */
    private List<String> dtoBasePackages = new ArrayList<>();

    /**
     * Quando verdadeiro, também inclui os pacotes raiz de {@code AutoConfigurationPackages} na
     * varredura de DTOs governados quando {@code dtoBasePackages} não for explicitamente restrito.
     */
    private boolean scanAutoConfigurationPackages = false;

    /** Chaves adicionais de critérios consideradas sensíveis em escopo compartilhado {@code tenant}. */
    private List<String> additionalSensitiveKeys = new ArrayList<>();

    public boolean isBlockSensitiveTenantCriteria() {
        return blockSensitiveTenantCriteria;
    }

    public void setBlockSensitiveTenantCriteria(boolean blockSensitiveTenantCriteria) {
        this.blockSensitiveTenantCriteria = blockSensitiveTenantCriteria;
    }

    public List<String> getDtoBasePackages() {
        return dtoBasePackages;
    }

    public void setDtoBasePackages(List<String> dtoBasePackages) {
        this.dtoBasePackages = dtoBasePackages != null ? new ArrayList<>(dtoBasePackages) : new ArrayList<>();
    }

    public boolean isScanAutoConfigurationPackages() {
        return scanAutoConfigurationPackages;
    }

    public void setScanAutoConfigurationPackages(boolean scanAutoConfigurationPackages) {
        this.scanAutoConfigurationPackages = scanAutoConfigurationPackages;
    }

    public List<String> getAdditionalSensitiveKeys() {
        return additionalSensitiveKeys;
    }

    public void setAdditionalSensitiveKeys(List<String> additionalSensitiveKeys) {
        this.additionalSensitiveKeys =
                additionalSensitiveKeys != null ? new ArrayList<>(additionalSensitiveKeys) : new ArrayList<>();
    }
}
