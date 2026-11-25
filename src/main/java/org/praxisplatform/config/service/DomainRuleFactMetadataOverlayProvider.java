package org.praxisplatform.config.service;

import java.util.List;
import org.praxisplatform.config.dto.DomainRuleDefinitionResponse;
import org.praxisplatform.config.dto.DomainRuleFactDescriptor;

/**
 * Read-only SPI allowing hosts or starter adapters to enrich persisted {@code factCatalog} entries
 * from live DTO / domain metadata at query time without mutating stored rule JSONB or SHA-256
 * definition fingerprints.
 */
@FunctionalInterface
public interface DomainRuleFactMetadataOverlayProvider {

    /**
     * Returns an overlaid list of fact descriptors for the given definition, keeping structural
     * identity and privacy constraints fail-closed.
     */
    List<DomainRuleFactDescriptor> enrich(
            DomainRuleDefinitionResponse definition,
            List<DomainRuleFactDescriptor> persistedFacts);
}
