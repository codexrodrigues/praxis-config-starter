package org.praxisplatform.config.controller;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.praxisplatform.config.dto.RegistryIngestionRequest;
import org.praxisplatform.config.service.RegistryIngestionService;
import org.praxisplatform.config.service.AiIntelligenceReleaseService;
import org.praxisplatform.config.service.CanonicalJsonHashService;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.fasterxml.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@Tag("unit")
class RegistryIngestionControllerTest {

    @Mock
    private RegistryIngestionService service;
    @Mock private AiIntelligenceReleaseService releaseService;
    @Mock private CanonicalJsonHashService hashService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new RegistryIngestionController(service, releaseService, hashService, new ObjectMapper())).build();
    }

    @Test
    void returnsReconciledPublicationReceipt() throws Exception {
        RegistryIngestionService.RegistryReindexResult receipt =
                new RegistryIngestionService.RegistryReindexResult(
                        "tenant-a",
                        "prod",
                        "registry-1.0.0",
                        "1.0.0",
                        1,
                        3,
                        3,
                        List.of(),
                        null);
        when(service.ingestRegistry(
                        org.mockito.ArgumentMatchers.any(RegistryIngestionRequest.class),
                        org.mockito.ArgumentMatchers.eq("tenant-a"),
                        org.mockito.ArgumentMatchers.eq("prod")))
                .thenReturn(receipt);

        mockMvc.perform(post("/api/praxis/config/ai-registry/component-definitions")
                        .header("X-Tenant-ID", "tenant-a")
                        .header("X-Env", "prod")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "version": "1.0.0",
                                  "components": {
                                    "praxis-table": {
                                      "description": "Tabela",
                                      "chunks": [{
                                        "chunkIndex": 0,
                                        "chunkKind": "component_summary",
                                        "content": "Tabela"
                                      }]
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.releaseId").value("registry-1.0.0"))
                .andExpect(jsonPath("$.componentCount").value(1))
                .andExpect(jsonPath("$.expectedChunkCount").value(3))
                .andExpect(jsonPath("$.publishedChunkCount").value(3));
    }

    @Test
    void governedPublicationObservesThePhysicalCorpusReleaseIdentity() throws Exception {
        RegistryIngestionService.RegistryReindexResult receipt =
                new RegistryIngestionService.RegistryReindexResult(
                        "tenant-a", "prod", "corpus-v1", "1.0.0", 1, 3, 3, List.of(), null);
        when(service.ingestRegistry(
                        org.mockito.ArgumentMatchers.any(RegistryIngestionRequest.class),
                        org.mockito.ArgumentMatchers.eq("tenant-a"),
                        org.mockito.ArgumentMatchers.eq("prod")))
                .thenReturn(receipt);
        when(hashService.sha256(org.mockito.ArgumentMatchers.any())).thenReturn("a".repeat(64));

        mockMvc.perform(post("/api/praxis/config/ai-registry/component-definitions")
                        .header("X-Tenant-ID", "tenant-a")
                        .header("X-Env", "prod")
                        .header("X-Praxis-Intelligence-Release", "publication-42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version":"1.0.0","components":{"praxis-table":{"chunks":[]}}}
                                """))
                .andExpect(status().isAccepted());

        verify(releaseService).observeComponents(
                "tenant-a", "prod", "publication-42", 1, "a".repeat(64), 3, "corpus-v1");
    }
}
