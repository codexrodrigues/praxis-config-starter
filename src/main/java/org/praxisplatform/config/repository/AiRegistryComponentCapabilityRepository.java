package org.praxisplatform.config.repository;

import java.util.List;
import org.praxisplatform.config.projection.AiRegistryComponentCapabilityProjection;

public interface AiRegistryComponentCapabilityRepository {

    List<AiRegistryComponentCapabilityProjection> findComponentCapabilityProjections(
            String registryType,
            String componentType,
            String scope,
            String scopeKey,
            long queryTimeoutMs);
}
