package org.praxisplatform.config.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.domain.DomainKnowledgeChangeSet;
import org.praxisplatform.config.repository.DomainKnowledgeAliasRepository;
import org.praxisplatform.config.repository.DomainKnowledgeBindingRepository;
import org.praxisplatform.config.repository.DomainKnowledgeChangeSetRepository;
import org.praxisplatform.config.repository.DomainKnowledgeConceptRepository;
import org.praxisplatform.config.repository.DomainKnowledgeEvidenceRepository;
import org.praxisplatform.config.repository.DomainKnowledgeRelationshipRepository;
import org.praxisplatform.config.service.DomainKnowledgeChangeSetService;
import org.praxisplatform.config.service.DomainKnowledgeChangeSetValidator;
import org.praxisplatform.config.service.ProjectKnowledgeDerivedIndexService;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@Tag("unit")
class DomainKnowledgeHumanResourcesSemanticPilotHttpTest {

    private static final Path PILOT = Path.of(
            "docs",
            "ai",
            "agentic-authoring",
            "proofs",
            "human-resources-semantic-pilot-change-set.v0.1.json");

    @Test
    void acceptsTheReferencePilotThroughThePublicHttpCreateContract() throws Exception {
        DomainKnowledgeChangeSetRepository changeSetRepository = mock(DomainKnowledgeChangeSetRepository.class);
        when(changeSetRepository.findByTenantIdAndEnvironmentAndChangeSetKey(
                "desenv", "local", "project-knowledge:human-resources:semantic-pilot:v0.1"))
                .thenReturn(Optional.empty());
        when(changeSetRepository.save(any(DomainKnowledgeChangeSet.class))).thenAnswer(invocation -> {
            DomainKnowledgeChangeSet changeSet = invocation.getArgument(0);
            changeSet.onInsert();
            return changeSet;
        });

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean(
                "projectKnowledgeDerivedIndexService",
                mock(ProjectKnowledgeDerivedIndexService.class));
        DomainKnowledgeChangeSetService service = new DomainKnowledgeChangeSetService(
                changeSetRepository,
                mock(DomainKnowledgeConceptRepository.class),
                mock(DomainKnowledgeAliasRepository.class),
                mock(DomainKnowledgeBindingRepository.class),
                mock(DomainKnowledgeRelationshipRepository.class),
                mock(DomainKnowledgeEvidenceRepository.class),
                new DomainKnowledgeChangeSetValidator(),
                new ObjectMapper(),
                beanFactory.getBeanProvider(ProjectKnowledgeDerivedIndexService.class));
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new DomainKnowledgeChangeSetController(service))
                .build();

        mockMvc.perform(post("/api/praxis/config/domain-knowledge/change-sets")
                        .header("X-Tenant-ID", "desenv")
                        .header("X-Env", "local")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(Files.readString(PILOT)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.changeSetKey")
                        .value("project-knowledge:human-resources:semantic-pilot:v0.1"))
                .andExpect(jsonPath("$.status").value("proposed"))
                .andExpect(jsonPath("$.validationStatus").value("valid"))
                .andExpect(jsonPath("$.operationCount").value(11))
                .andExpect(jsonPath("$.validationResult.nonExecutableOperationTypes").isEmpty())
                .andExpect(jsonPath("$.validationResult.executablePatchOperationTypes").isArray());
    }
}
