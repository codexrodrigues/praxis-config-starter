package org.praxisplatform.config.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.praxisplatform.config.PraxisConfigStarterApplication;
import org.praxisplatform.config.domain.AiThread;
import org.praxisplatform.config.domain.AiTurn;
import org.praxisplatform.config.domain.AiTurnStatus;
import org.praxisplatform.config.service.AiPrincipalContext;
import org.praxisplatform.config.service.AiSensitiveDataRedactor;
import org.praxisplatform.config.service.AiTurnEventService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/** Real PostgreSQL, separate service instances/transactions; no shared JVM admission lock. */
@DataJpaTest(showSql = false)
@ContextConfiguration(classes = {PraxisConfigStarterApplication.class, AiAuthoringDecisionAdmissionConcurrencyTest.Transactions.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext
@Tag("integration")
class AiAuthoringDecisionAdmissionConcurrencyTest {
    @org.springframework.boot.test.context.TestConfiguration
    static class Transactions {
        @org.springframework.context.annotation.Bean(name = {"transactionManager", "configTransactionManager"})
        PlatformTransactionManager transactionManager(jakarta.persistence.EntityManagerFactory factory) {
            return new org.springframework.orm.jpa.JpaTransactionManager(factory);
        }
    }

    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final AiPrincipalContext PRINCIPAL = new AiPrincipalContext("tenant-a", "user-a", "prod", true);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @Autowired AiThreadRepository threads;
    @Autowired AiTurnRepository turns;
    @Autowired AiTurnEventRepository events;
    @Autowired PlatformTransactionManager transactionManager;
    private UUID threadId;
    private TransactionTemplate transactions;
    private AiTurnEventService writer;
    private AiTurnEventService reader;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    @AfterAll static void closePostgres() throws IOException { POSTGRES.close(); }

    @BeforeEach void setup() {
        transactions = new TransactionTemplate(transactionManager);
        writer = service();
        reader = service();
        threadId = UUID.randomUUID();
        transactions.executeWithoutResult(ignored -> threads.saveAndFlush(AiThread.builder()
                .threadId(threadId).tenantId("tenant-a").userId("user-a").environment("prod")
                .componentType("page-builder").componentId("synthetic-page").build()));
        UUID parentTurn = reserveTurn();
        transactions.executeWithoutResult(ignored -> publish(writer, parentTurn, "parent"));
    }

    @Test void committedResultWinsAgainstPreviouslyResolvedOptionOnAnotherInstance() throws Exception {
        var resolved = transactions.execute(ignored -> reader.findPersistedSemanticDecision(threadId, "parent-option", PRINCIPAL));
        assertThat(resolved).isPresent();
        UUID resultTurn = reserveTurn();
        UUID startTurn = reserveTurn();
        CountDownLatch resultWritten = new CountDownLatch(1);
        CountDownLatch releaseResult = new CountDownLatch(1);
        CountDownLatch startAttempted = new CountDownLatch(1);
        AtomicInteger waitingPid = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var result = executor.submit(() -> transactions.executeWithoutResult(ignored -> {
                publish(writer, resultTurn, "next");
                resultWritten.countDown();
                await(releaseResult);
            }));
            try {
                assertThat(resultWritten.await(5, TimeUnit.SECONDS)).isTrue();
                var admission = executor.submit(() -> transactions.execute(ignored -> {
                    waitingPid.set(jdbc().queryForObject("select pg_backend_pid()", Integer.class));
                    startAttempted.countDown();
                    return start(reader, startTurn, "parent-option");
                }));
                assertThat(startAttempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertDatabaseLockWait(waitingPid.get());
                assertThatThrownBy(() -> admission.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                releaseResult.countDown();
                result.get(5, TimeUnit.SECONDS);
                assertThatThrownBy(() -> admission.get(5, TimeUnit.SECONDS))
                        .hasRootCauseInstanceOf(ResponseStatusException.class)
                        .hasStackTraceContaining("active-semantic-decision-not-current-in-thread");
                assertThat(events.findFirstByThreadIdAndTurnIdOrderBySeqAsc(threadId, startTurn)).isEmpty();
            } finally { releaseResult.countDown(); }
        }
    }

    @Test void admittedStartWinsAndResultWaitsUntilAdmissionCommits() throws Exception {
        UUID startTurn = reserveTurn();
        UUID resultTurn = reserveTurn();
        CountDownLatch startWritten = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        CountDownLatch resultAttempted = new CountDownLatch(1);
        AtomicInteger waitingPid = new AtomicInteger();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var admission = executor.submit(() -> transactions.execute(ignored -> {
                var start = start(reader, startTurn, "parent-option");
                startWritten.countDown();
                await(releaseStart);
                return start;
            }));
            try {
                assertThat(startWritten.await(5, TimeUnit.SECONDS)).isTrue();
                var result = executor.submit(() -> transactions.executeWithoutResult(ignored -> {
                    waitingPid.set(jdbc().queryForObject("select pg_backend_pid()", Integer.class));
                    resultAttempted.countDown();
                    publish(writer, resultTurn, "next");
                }));
                assertThat(resultAttempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertDatabaseLockWait(waitingPid.get());
                assertThatThrownBy(() -> result.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                releaseStart.countDown();
                assertThat(admission.get(5, TimeUnit.SECONDS).appended()).isTrue();
                result.get(5, TimeUnit.SECONDS);
                var replay = transactions.execute(ignored -> start(reader, startTurn, "parent-option"));
                assertThat(replay.appended()).isFalse();
                assertThat(replay.event().getStreamId()).isEqualTo(admission.get().event().getStreamId());
            } finally { releaseStart.countDown(); }
        }
    }

    @Test void rejectsDecisionFreeAdmissionWhenAResultWasPublishedAfterResolution() {
        UUID turn = reserveTurn();
        assertThatThrownBy(() -> transactions.execute(ignored -> start(reader, turn, "")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("active-semantic-decision-not-current-in-thread");
        assertThat(events.findFirstByThreadIdAndTurnIdOrderBySeqAsc(threadId, turn)).isEmpty();
    }

    @Test void acceptsCurrentParentAndItsStoredOptionButRejectsUnknownOrForeignChoices() {
        for (String decision : List.of("parent", "parent-option")) {
            UUID turn = reserveTurn();
            assertThat(transactions.execute(ignored -> start(reader, turn, decision)).appended()).isTrue();
        }
        UUID unknown = reserveTurn();
        assertThatThrownBy(() -> transactions.execute(ignored -> start(reader, unknown, "forged-option")))
                .isInstanceOf(ResponseStatusException.class);
        UUID foreign = reserveTurn();
        assertThatThrownBy(() -> transactions.execute(ignored -> reader.appendStartEventIfAbsent(
                new AiPrincipalContext("tenant-a", "other-user", "prod", true), UUID.randomUUID(), threadId, foreign,
                startPayload("parent-option")))).isInstanceOf(ResponseStatusException.class);
    }

    private org.springframework.jdbc.core.JdbcTemplate jdbc() {
        return new org.springframework.jdbc.core.JdbcTemplate(
                ((org.springframework.orm.jpa.JpaTransactionManager) transactionManager).getDataSource());
    }

    private void assertDatabaseLockWait(int backendPid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        String waitType = null;
        while (System.nanoTime() < deadline) {
            waitType = jdbc().queryForObject("select wait_event_type from pg_stat_activity where pid = ?", String.class, backendPid);
            if ("Lock".equals(waitType)) return;
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat(waitType).as("second transaction must actually wait on a PostgreSQL lock").isEqualTo("Lock");
    }

    private AiTurnEventService.StreamStartAppendResult start(AiTurnEventService service, UUID turnId, String decisionId) {
        return service.appendStartEventIfAbsent(PRINCIPAL, UUID.randomUUID(), threadId, turnId, startPayload(decisionId));
    }

    private Map<String, Object> startPayload(String decisionId) {
        return Map.of("state", "started", "phase", "context.bundle", "requestHash", "synthetic-hash",
                "activeSemanticDecisionId", decisionId, "expiresAt", Instant.now().plusSeconds(900).toString());
    }

    private void publish(AiTurnEventService service, UUID turnId, String decisionId) {
        service.appendEvent(PRINCIPAL, UUID.randomUUID(), threadId, turnId, "result", Map.of(
                "intentResolution", Map.of("semanticDecision", Map.of("decisionId", decisionId)),
                "quickReplies", List.of(Map.of("semanticDecision", Map.of("decisionId", decisionId + "-option",
                        "previousDecisionId", decisionId, "constraints", Map.of("source", "server-issued-quick-reply"))))));
    }

    private UUID reserveTurn() {
        UUID turn = UUID.randomUUID();
        transactions.executeWithoutResult(ignored -> turns.saveAndFlush(AiTurn.builder().threadId(threadId).turnId(turn)
                .status(AiTurnStatus.PROCESSING).expiresAt(Instant.now().plusSeconds(900)).build()));
        return turn;
    }

    private AiTurnEventService service() {
        var service = new AiTurnEventService(events, turns, threads, MAPPER, new AiSensitiveDataRedactor());
        ReflectionTestUtils.setField(service, "eventSchemaVersion", "v1");
        ReflectionTestUtils.setField(service, "streamExpirySeconds", 900L);
        // Same Spring-managed data source/transaction as the repositories: exercises PostgreSQL CTE append.
        ReflectionTestUtils.setField(service, "configJdbcTemplate", new NamedParameterJdbcTemplate(
                ((org.springframework.orm.jpa.JpaTransactionManager) transactionManager).getDataSource()));
        return service;
    }

    private static EmbeddedPostgres startPostgres() {
        EmbeddedPostgres postgres = null;
        try {
            postgres = EmbeddedPostgres.builder().setCleanDataDirectory(true).setRegisterShutdownHook(false).start();
            try (var connection = postgres.getPostgresDatabase().getConnection(); var statement = connection.createStatement()) {
                for (String name : List.of("V13__create_ai_thread_message_action.sql", "V14__create_ai_turn.sql",
                        "V15__create_ai_turn_event.sql", "V34__allocate_ai_turn_event_sequence_on_turn.sql")) {
                    try (var stream = AiAuthoringDecisionAdmissionConcurrencyTest.class.getResourceAsStream("/db/migration/" + name)) {
                        if (stream == null) throw new IOException("Missing migration " + name);
                        statement.execute(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
            return postgres;
        } catch (Exception exception) {
            if (postgres != null) try { postgres.close(); } catch (IOException ignored) { }
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for transaction release");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
