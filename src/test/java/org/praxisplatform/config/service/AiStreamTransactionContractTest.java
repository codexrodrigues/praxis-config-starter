package org.praxisplatform.config.service;

import org.junit.jupiter.api.Tag;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.dto.AiOrchestratorRequest;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Tag("unit")
class AiStreamTransactionContractTest {

    @Test
    void aiTurnEventServiceMustUseConfigTransactionManager() throws NoSuchMethodException {
        Method appendSimple = AiTurnEventService.class.getMethod(
                "appendEvent",
                AiPrincipalContext.class,
                UUID.class,
                UUID.class,
                UUID.class,
                String.class,
                Object.class);
        Method appendWithEventId = AiTurnEventService.class.getMethod(
                "appendEvent",
                AiPrincipalContext.class,
                UUID.class,
                UUID.class,
                UUID.class,
                String.class,
                Object.class,
                UUID.class);

        assertTxManager(appendSimple, ConfigTransactionManagerNames.CONFIG);
        assertTxManager(appendWithEventId, ConfigTransactionManagerNames.CONFIG);
    }

    @Test
    void aiTurnServiceMustUseConfigTransactionManagerWithRequiresNewForMutations() throws NoSuchMethodException {
        Method beginTurn = AiTurnService.class.getMethod("beginTurn", UUID.class, UUID.class);
        Method reserveTurn = AiTurnService.class.getMethod("reserveTurnForStreaming", UUID.class, UUID.class);
        Method completeTurn = AiTurnService.class.getMethod("completeTurn", UUID.class, UUID.class);
        Method expireTurn = AiTurnService.class.getMethod("expireTurn", UUID.class, UUID.class);
        Method cancelTurn = AiTurnService.class.getMethod("cancelTurn", UUID.class, UUID.class);

        assertTxManager(beginTurn, ConfigTransactionManagerNames.CONFIG);
        assertTxManager(reserveTurn, ConfigTransactionManagerNames.CONFIG);
        assertTxManager(completeTurn, ConfigTransactionManagerNames.CONFIG);
        assertTxManager(expireTurn, ConfigTransactionManagerNames.CONFIG);
        assertTxManager(cancelTurn, ConfigTransactionManagerNames.CONFIG);

        assertPropagation(beginTurn, Propagation.REQUIRES_NEW);
        assertPropagation(reserveTurn, Propagation.REQUIRES_NEW);
        assertPropagation(completeTurn, Propagation.REQUIRES_NEW);
        assertPropagation(expireTurn, Propagation.REQUIRES_NEW);
        assertPropagation(cancelTurn, Propagation.REQUIRES_NEW);
    }

    @Test
    void aiMessageServiceMustUseConfigTransactionManagerForTurnLifecycleMethods() throws NoSuchMethodException {
        Method prepareTurn = AiMessageService.class.getMethod(
                "prepareTurn",
                org.praxisplatform.config.domain.AiThread.class,
                AiOrchestratorRequest.class,
                String.class);
        Method storeAssistant = AiMessageService.class.getMethod(
                "storeAssistantResponse",
                AiMemoryContext.class,
                org.praxisplatform.config.dto.AiOrchestratorResponse.class);
        Method summarize = AiMessageService.class.getMethod("summarizeIfNeeded", AiMemoryContext.class);

        assertTxManager(prepareTurn, ConfigTransactionManagerNames.CONFIG);
        assertTxManager(storeAssistant, ConfigTransactionManagerNames.CONFIG);
        assertTxManager(summarize, ConfigTransactionManagerNames.CONFIG);
    }

    private void assertTxManager(Method method, String expectedManager) {
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertNotNull(transactional, "Method must be transactional: " + method.getName());
        assertEquals(expectedManager, transactional.transactionManager(), "Unexpected tx manager for " + method.getName());
    }

    private void assertPropagation(Method method, Propagation expectedPropagation) {
        Transactional transactional = method.getAnnotation(Transactional.class);
        assertNotNull(transactional, "Method must be transactional: " + method.getName());
        assertEquals(expectedPropagation, transactional.propagation(), "Unexpected propagation for " + method.getName());
    }
}
