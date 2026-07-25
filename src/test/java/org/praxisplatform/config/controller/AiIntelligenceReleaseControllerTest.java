package org.praxisplatform.config.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.rag.RagVectorStoreService;
import org.praxisplatform.config.service.AiIntelligenceReleaseService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class AiIntelligenceReleaseControllerTest {
    @Mock private AiIntelligenceReleaseService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AiIntelligenceReleaseController(service)).build();
    }

    @Test
    void cleanupPlanForwardsCanonicalScopeAndReturnsReadOnlyCandidates() throws Exception {
        var plan = new RagVectorStoreService.SupersededReleaseCleanupPlan(
                "tenant", "prod", "corpus-v3", "component_definition", 12,
                List.of(new RagVectorStoreService.SupersededReleaseDocuments("release-2", 12)));
        when(service.cleanupPlan("tenant", "prod", "release-3")).thenReturn(plan);

        mockMvc.perform(get("/api/praxis/config/ai-registry/releases/release-3/cleanup-plan")
                        .header("X-Tenant-ID", "tenant")
                        .header("X-Env", "prod"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeReleaseId").value("corpus-v3"))
                .andExpect(jsonPath("$.resourceType").value("component_definition"))
                .andExpect(jsonPath("$.documentCount").value(12))
                .andExpect(jsonPath("$.releases[0].releaseId").value("release-2"))
                .andExpect(jsonPath("$.releases[0].documentCount").value(12));

        verify(service).cleanupPlan("tenant", "prod", "release-3");
    }
}
