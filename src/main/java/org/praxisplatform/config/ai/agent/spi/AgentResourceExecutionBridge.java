package org.praxisplatform.config.ai.agent.spi;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Porta em processo que executa descoberta de topicos/schemas, resolucao polimorfica, consultas e
 * montagem de dossie 360 diretamente sobre os servicos Spring do host.
 */
public interface AgentResourceExecutionBridge {

    List<AgentTopicDescriptor> listTopics(AgentExecutionContext context);

    List<AgentEntityDescriptor> listEntities(AgentExecutionContext context, String topicOrQuery);

    Optional<CompactAgentSchemaDigest> describeEntitySchema(
            AgentExecutionContext context,
            String entityKeyOrResourcePath);

    List<AgentEntityMatch> searchEntities(
            AgentExecutionContext context,
            AgentEntitySearchRequest request);

    AgentQueryResult queryRecords(
            AgentExecutionContext context,
            String entityKeyOrResourcePath,
            Map<String, Object> filters,
            int page,
            int size,
            String sort);

    Optional<AgentEntityDossier> loadEntityDossier(
            AgentExecutionContext context,
            String entityKeyOrResourcePath,
            String entityId,
            List<String> includeRelatedSurfaces);

    default AgentStatsResult aggregateStats(
            AgentExecutionContext context,
            AgentStatsAggregationRequest request) {
        throw new UnsupportedOperationException("Agregacao analitica nao suportada por esta bridge");
    }

    default AgentStatsResult executeVerifiedAnalyticalRecipe(
            AgentExecutionContext context,
            AgentVerifiedRecipeExecutionRequest request) {
        throw new UnsupportedOperationException("Execucao de receita analitica verificada nao suportada por esta bridge");
    }

    default AgentStatisticalAnalysisResult runStatisticalInference(
            AgentExecutionContext context,
            AgentStatisticalInferenceRequest request) {
        throw new UnsupportedOperationException("Inferencia estatistica nao suportada por esta bridge");
    }

    default AgentStatisticalAnalysisResult scanResourceAnomalies(
            AgentExecutionContext context,
            AgentAnomalyScanRequest request) {
        throw new UnsupportedOperationException("Varredura de anomalias nao suportada por esta bridge");
    }

    default AgentStatisticalAnalysisResult runStochasticSimulation(
            AgentExecutionContext context,
            AgentStochasticSimulationRequest request) {
        throw new UnsupportedOperationException("Simulacao estocastica nao suportada por esta bridge");
    }

    default AgentStatisticalAnalysisResult runCounterfactualSearch(
            AgentExecutionContext context,
            AgentCounterfactualSearchRequest request) {
        throw new UnsupportedOperationException("Busca contrafactual nao suportada por esta bridge");
    }

    default List<AgentEvaluatedCalculationMemory> evaluateCalculationMemory(
            AgentExecutionContext context,
            String entityKeyOrResourcePath,
            String entityId,
            Map<String, Object> filters,
            int maxRecords) {
        return List.of();
    }

    default AgentSimulationPreview simulateDetermination(
            AgentExecutionContext context,
            AgentDeterminationRequest request) {
        throw new UnsupportedOperationException("Simulacao de determinacoes nao suportada por esta bridge");
    }

    default AgentPreparedAction prepareAction(
            AgentExecutionContext context,
            AgentPrepareActionRequest request) {
        throw new UnsupportedOperationException("Preparacao de acoes governadas nao suportada por esta bridge");
    }

    default AgentPreparedBulkOperation prepareBulkOperation(
            AgentExecutionContext context,
            AgentPrepareBulkRequest request) {
        throw new UnsupportedOperationException("Preparacao de operacoes em lote governadas nao suportada por esta bridge");
    }

    default AgentActionExecutionOutcome executeConfirmedAction(
            AgentExecutionContext context,
            AgentExecuteActionRequest request) {
        throw new UnsupportedOperationException("Execucao de acoes governadas nao suportada por esta bridge");
    }
}
