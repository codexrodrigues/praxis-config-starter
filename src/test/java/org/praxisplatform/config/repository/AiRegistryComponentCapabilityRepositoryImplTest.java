package org.praxisplatform.config.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.AiRegistry;
import org.springframework.data.jpa.repository.JpaContext;

@Tag("unit")
class AiRegistryComponentCapabilityRepositoryImplTest {

    @Test
    void appliesTheGovernedTimeoutToTheDatabaseStatementAndMapsOnlyRequiredFragments() {
        EntityManager entityManager = mock(EntityManager.class);
        JpaContext jpaContext = mock(JpaContext.class);
        Query query = mock(Query.class);
        when(jpaContext.getEntityManagerByManagedType(AiRegistry.class)).thenReturn(entityManager);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(query);
        when(query.setHint(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(query);
        when(query.getResultList()).thenReturn(java.util.Collections.singletonList(new Object[] {
                "praxis-chart",
                "Gráfico governado",
                "Praxis Chart",
                "praxis-chart",
                "[\"chart\",\"analytics\"]",
                "{\"componentId\":\"praxis-chart\",\"operations\":[]}"
        }));

        AiRegistryComponentCapabilityRepositoryImpl repository =
                new AiRegistryComponentCapabilityRepositoryImpl(jpaContext);
        var result = repository.findComponentCapabilityProjections(
                "component_definition",
                "component-definition",
                "SYSTEM",
                "GLOBAL",
                1_234L);

        assertThat(result).singleElement().satisfies(projection -> {
            assertThat(projection.registryKey()).isEqualTo("praxis-chart");
            assertThat(projection.friendlyName()).isEqualTo("Praxis Chart");
            assertThat(projection.authoringManifestJson()).contains("praxis-chart");
        });
        verify(query).setHint("jakarta.persistence.query.timeout", 1_234);
        verify(query).setParameter("scope", "SYSTEM");
        verify(query).getResultList();
    }
}
