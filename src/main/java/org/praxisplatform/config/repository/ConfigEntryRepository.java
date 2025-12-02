package org.praxisplatform.config.repository;

import org.praxisplatform.config.domain.ConfigEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ConfigEntryRepository extends JpaRepository<ConfigEntry, UUID> {

    Optional<ConfigEntry> findByConfigKey(String configKey);
}
