package org.praxisplatform.config.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * SPI opcional para hosts que co-alocam geração semântica de catálogo ({@code /schemas/domain})
 * e ingestão governada ({@link DomainCatalogIngestionService}) no mesmo processo.
 */
@FunctionalInterface
public interface DomainCatalogLocalSourceProvider {

    List<JsonNode> localCatalogDocuments();
}
