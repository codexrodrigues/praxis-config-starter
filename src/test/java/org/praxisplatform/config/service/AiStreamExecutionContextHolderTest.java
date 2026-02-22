package org.praxisplatform.config.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AiStreamExecutionContextHolderTest {

    @Test
    void abortStreamTriggersRegisteredAbortAction() {
        UUID streamId = UUID.randomUUID();
        AtomicInteger abortCalls = new AtomicInteger(0);

        AiStreamExecutionContextHolder.execute(
                streamId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                () -> false,
                () -> {
                    AiStreamExecutionContextHolder.registerAbortAction(abortCalls::incrementAndGet);
                    AiStreamExecutionContextHolder.abortStream(streamId);
                    return null;
                });

        assertEquals(1, abortCalls.get());
    }

    @Test
    void abortRegistrationClosePreventsAbortExecution() {
        UUID streamId = UUID.randomUUID();
        AtomicInteger abortCalls = new AtomicInteger(0);

        AiStreamExecutionContextHolder.execute(
                streamId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                () -> false,
                () -> {
                    AiStreamExecutionContextHolder.AbortRegistration registration =
                            AiStreamExecutionContextHolder.registerAbortAction(abortCalls::incrementAndGet);
                    registration.close();
                    AiStreamExecutionContextHolder.abortStream(streamId);
                    return null;
                });

        assertEquals(0, abortCalls.get());
    }
}
