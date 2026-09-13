package org.praxisplatform.config.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.praxisplatform.config.exception.ConfigGlobalExceptionHandler;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

class DomainRuleConcurrencyResponseTest {
    @Test
    void staleUpdateExplainsReloadWithoutExposingEntityOrDatabaseDetails() throws Exception {
        MockMvcBuilders.standaloneSetup(new ConflictingController())
                .setControllerAdvice(new ConfigGlobalExceptionHandler()).build()
                .perform(post("/api/praxis/config/domain-rules/test-concurrency"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "The decision changed during this operation. Reload its current state before trying again."))
                .andExpect(jsonPath("$.cause").doesNotExist());
    }

    @RestController
    static class ConflictingController {
        @PostMapping("/api/praxis/config/domain-rules/test-concurrency")
        void change() {
            throw new ObjectOptimisticLockingFailureException("private.entity", "private-identifier");
        }
    }
}
