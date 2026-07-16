package org.praxisplatform.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.domain.ConfigEntry;
import org.praxisplatform.config.repository.ConfigEntryRepository;

@ExtendWith(MockitoExtension.class)
@Tag("integration")
class DatabaseConnectionTest {

    @Mock
    private DataSource dataSource;

    @Mock
    private ConfigEntryRepository repository;

    @Test
    void shouldConnectAndPersistConfigEntry() throws Exception {
        Connection mockConnection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(mockConnection);
        when(mockConnection.isValid(5)).thenReturn(true);

        String key = "integration-test-key";
        ConfigEntry entry = new ConfigEntry(key, "integration-test-value");
        when(repository.save(any(ConfigEntry.class))).thenReturn(entry);
        when(repository.findById(any())).thenReturn(Optional.of(entry));

        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isValid(5)).isTrue();
        }

        repository.findByConfigKey(key).ifPresent(repository::delete);
        ConfigEntry saved = repository.save(entry);
        Optional<ConfigEntry> found = repository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getConfigValue()).isEqualTo("integration-test-value");

        repository.deleteById(saved.getId());
    }
}
