package org.praxisplatform.config.ai.authoring;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.praxisplatform.config.dto.AiSchemaContext;
import org.praxisplatform.config.service.*;

/** Synthetic semantic/catalog boundary; real engine, child resolver and compiler. */
final class AgenticAuthoringConsultativePersistenceFixture {
    static AgenticAuthoringTurnEngine engine(ObjectMapper mapper, String domain) {
        String resource = "/api/synthetic/" + domain;
        var catalog = mock(AgenticAuthoringApiMetadataCandidateCatalog.class);
        var candidate = new AgenticAuthoringCandidate(resource, "post",
                "/schemas/filtered?path=" + resource + "/filter&operation=post&schemaType=response",
                resource + "/filter", "POST", 0.98, "Synthetic governed resource",
                List.of("semantic-retrieval", "api-metadata", "schema-grounding-verified"));
        when(catalog.discover(anyString(), anyString(), any(), any(), any())).thenReturn(List.of(candidate));
        var semanticProvider = mock(AgenticAuthoringLlmIntentResolverService.class);
        var resolver = spy(new AgenticAuthoringIntentResolverService(mapper, catalog, semanticProvider, null));
        doAnswer(call -> {
            AgenticAuthoringIntentResolutionRequest request = call.getArgument(0);
            if (request.activeSemanticDecision() != null) return call.callRealMethod();
            return new AgenticAuthoringIntentResolutionResult(true, "explore", "api_catalog",
                    "answer_api_catalog_question", "api-catalog-qa", "praxis-ui-angular",
                    "praxis-dynamic-page-builder", null, null, List.of(),
                    new AgenticAuthoringGateResult("eligible", "eligible", List.of()),
                    request.userPrompt(), "Posso explicar os dados disponíveis.", List.of(), List.of(),
                    List.of(), List.of(), mapper.createObjectNode());
        }).when(resolver).resolve(any(), any(), any(), any());
        var answers = mock(AgenticAuthoringConsultativeAnswerService.class);
        when(answers.answer(any(AgenticAuthoringTurnStreamRequest.class), any(), any(), any(), any()))
                .thenReturn(Optional.of(new AgenticAuthoringConsultativeAnswer("domain_api",
                        "answer_api_catalog_question", "Você pode criar uma lista dos registros disponíveis.",
                        new AgenticAuthoringConsultativeApiCatalogProjection(domain, "Registros disponíveis.",
                                List.of(new AgenticAuthoringConsultativeApiCatalogProjection.Resource(
                                        "synthetic." + domain, resource, domain, "operational",
                                        "Registros sintéticos", List.of(), List.of(), List.of(),
                                        List.of("domain_catalog_context"))), List.of()), List.of())));
        var schemas = mock(SchemaRetrievalService.class);
        var schema = mapper.createObjectNode().put("type", "object");
        schema.putObject("properties").putObject("id").put("type", "integer")
                .putObject("x-ui").put("controlType", "number");
        when(schemas.fetchSchemaResult(any(AiSchemaContext.class), any()))
                .thenReturn(SchemaFetchResult.success(schema, "http://localhost:8088/schemas/filtered"));
        var provider = mock(AiProviderManagementService.class);
        var artifacts = new AgenticAuthoringArtifactProperties();
        var preview = new AgenticAuthoringPreviewService(new AgenticAuthoringPlanService(provider, artifacts, mapper),
                new AgenticAuthoringPatchCompilerService(artifacts, mapper), mapper,
                List.of(new AgenticAuthoringGenericUiCompositionPlanProvider(mapper)), null, schemas);
        return new AgenticAuthoringTurnEngine(resolver, preview, mapper,
                new AgenticAuthoringCurrentPageAnalyzer(mapper),
                new AgenticAuthoringToolRegistry(new AgenticAuthoringResourceDiscoveryService(catalog, mapper)),
                null, null, null, new AgenticAuthoringComponentCapabilitiesService(), answers);
    }

    private AgenticAuthoringConsultativePersistenceFixture() { }
}
