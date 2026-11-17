package org.praxisplatform.config.service;

import java.util.Set;

/**
 * SPI interno/extensível para descoberta de chaves de filtro ou campos governados sensíveis que
 * não devem ser persistidos em visões compartilhadas de escopo {@code tenant}.
 */
@FunctionalInterface
public interface SensitiveCriteriaDiscoverySource {

    /**
     * Retorna um conjunto imutável de chaves normalizadas (minúsculas, sem separadores) consideradas
     * sensíveis para proteção de critérios compartilhados.
     */
    Set<String> discoverNormalizedSensitiveKeys();
}
