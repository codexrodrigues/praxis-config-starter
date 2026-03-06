package org.praxisplatform.config.repository;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.tx.ConfigTransactionManagerNames;
import org.springframework.transaction.annotation.Transactional;

class AiTurnRepositoryContractTest {

    @Test
    void findByThreadIdAndTurnIdForUpdateMustBeTransactional() throws NoSuchMethodException {
        Method method =
                AiTurnRepository.class.getMethod("findByThreadIdAndTurnIdForUpdate", UUID.class, UUID.class);
        Transactional transactional = method.getAnnotation(Transactional.class);

        assertNotNull(transactional, "Pessimistic lock query must run inside transaction.");
        assertTrue(!transactional.readOnly(), "Lock query cannot be read-only.");
        assertTrue(
                ConfigTransactionManagerNames.CONFIG.equals(transactional.transactionManager()),
                "Pessimistic lock query must use config transaction manager.");
    }
}
