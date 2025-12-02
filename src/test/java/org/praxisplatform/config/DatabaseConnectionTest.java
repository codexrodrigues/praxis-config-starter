package org.praxisplatform.config;

import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.ConfigEntry;
import org.praxisplatform.config.repository.ConfigEntryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DatabaseConnectionTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ConfigEntryRepository repository;

    @Test
    void shouldConnectAndPersistConfigEntry() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isValid(5)).isTrue();
        }

        String key = "integration-test-key";
        repository.findByConfigKey(key).ifPresent(repository::delete);

        ConfigEntry entry = new ConfigEntry(key, "integration-test-value");
        ConfigEntry saved = repository.save(entry);

        Optional<ConfigEntry> found = repository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getConfigValue()).isEqualTo("integration-test-value");

        repository.deleteById(saved.getId());
    }
}
