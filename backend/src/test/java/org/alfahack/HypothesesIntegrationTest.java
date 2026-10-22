package org.alfahack;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.alfahack.core.domain.User;
import org.alfahack.core.errors.DomainException;
import org.alfahack.core.serialization.JsonCodec;
import org.alfahack.features.insights.port.HypothesisModel;
import org.alfahack.features.insights.port.HypothesisUseCases;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:hypotheses;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa", "spring.datasource.password=", "app.insights.worker-enabled=false"})
@AutoConfigureMockMvc
class HypothesesIntegrationTest {

    static final User STABLE = new User("stable", "CLIENT", "STABLE", "test");
    static final User GAP = new User("gap", "CLIENT", "GAP", "test");
    @Autowired
    HypothesisUseCases service;
    @Autowired
    JdbcTemplate db;
    @Autowired
    ControlledModel model;
    @Autowired
    MockMvc mvc;
    @Autowired
    JsonCodec json;

    @TestConfiguration
    static class Config {

        @Bean
        @Primary
        ControlledModel controlledModel() {
            return new ControlledModel();
        }
    }

    static class ControlledModel implements HypothesisModel {

        AtomicInteger calls = new AtomicInteger();
        volatile String mode = "OK";
        volatile boolean enabled = true;
        CountDownLatch secondStarted = new CountDownLatch(1), release = new CountDownLatch(1);
        public boolean available() {
            return enabled;
        }

        public Map<String, Object> explain(Map<String, Object> context) throws Exception {
            int call = calls.incrementAndGet();
            assertTrue(context.containsKey("calculations"));
            assertTrue(context.containsKey("candidate"));
            if (mode.equals("FAIL"))
                throw new IllegalStateException("provider-secret-error");
            if (mode.equals("EMPTY"))
                return Map.of("hypotheses", List.of(), "questions", List.of());
            if (mode.equals("BLOCK") && call == 2) {
                secondStarted.countDown();
                if (!release.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("Test timed out");
            }
            return Map.of("hypotheses", List.of(Map.of("title", "Проверить возможность",
                "rationale", "Объяснение по переданным данным", "experiment", "Проверить спрос",
                "success_metric", "Денежный поток")), "questions", List.of("Каков бюджет?"));
        }
    }

    @BeforeEach
    void reset() {
        db.update("DELETE FROM insight_reads");
        db.update("DELETE FROM business_hypotheses");
        db.update("DELETE FROM insight_jobs");
        model.mode = "OK";
        model.calls.set(0);
        model.enabled = true;
        model.secondStarted = new CountDownLatch(1);
        model.release = new CountDownLatch(1);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> cards(Map<String, Object> value) {
        return (List<Map<String, Object>>) value.get("cards");
    }

    @Test
    void requestsOnlyEnqueueAndReuseTheSameSnapshot() {
        var first = service.list(STABLE);
        var second = service.list(STABLE);
        assertEquals("QUEUED", object(first.get("job")).get("status"));
        assertEquals(first.get("job"), second.get("job"));
        assertEquals(0, model.calls.get());
        assertTrue(cards(first).isEmpty());
        service.processNext();
        var ready = service.list(STABLE);
        assertEquals("READY", object(ready.get("job")).get("status"));
        assertFalse(cards(ready).isEmpty());
        assertEquals("LLM", cards(ready).getFirst().get("explanation_source"));
        assertTrue(String.valueOf(cards(ready).getFirst().get("title")).startsWith("Проверить: "));
        assertEquals("NEEDS_DATA", cards(ready).getFirst().get("status"));
        assertFalse(object(cards(ready).getFirst().get("financing")).isEmpty());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM insight_jobs", Integer.class));
        assertEquals(cards(ready).size(), service.notifications(STABLE).size());
    }

    @Test
    void cardsAndNotificationsAppearBeforeTheJobCompletes() throws Exception {
        model.mode = "BLOCK";
        service.list(STABLE);
        var task = CompletableFuture.runAsync(service::processNext);
        try {
            assertTrue(model.secondStarted.await(10, TimeUnit.SECONDS));
            var partial = service.list(STABLE);
            assertEquals("RUNNING", object(partial.get("job")).get("status"));
            assertEquals(1, cards(partial).size());
            assertEquals(1, service.notifications(STABLE).size());
        } finally {
            model.release.countDown();
            task.get(10, TimeUnit.SECONDS);
        }
        assertEquals("READY", object(service.list(STABLE).get("job")).get("status"));
    }

    @Test
    void providerFailurePreservesRuleBasedCardsAndRetryDoesNotDuplicateNotifications() {
        model.mode = "FAIL";
        service.list(STABLE);
        service.processNext();
        var partial = service.list(STABLE);
        assertEquals("PARTIAL", object(partial.get("job")).get("status"));
        assertEquals("RULES", cards(partial).getFirst().get("explanation_source"));
        int count = service.notifications(STABLE).size();
        String id = (String) cards(partial).getFirst().get("id");
        service.read(STABLE, id);
        service.read(STABLE, id);
        assertTrue((Boolean) service.notifications(STABLE).stream().filter(n -> n.get("id").equals(id)).findFirst()
            .orElseThrow().get("read"));
        model.mode = "OK";
        service.retry(STABLE);
        service.processNext();
        assertEquals("READY", object(service.list(STABLE).get("job")).get("status"));
        assertEquals(count, service.notifications(STABLE).size());
        assertEquals("LLM", service.detail(STABLE, id).get("explanation_source"));
        assertFalse(service.list(STABLE).toString().contains("provider-secret-error"));
    }

    @Test
    void noEvidenceOrNoModelHypothesisIsAnExplicitEmptyResult() {
        db.update("INSERT INTO companies (id,name,balance) VALUES ('QUIET','Quiet business',100000)");
        try {
            var quiet = new User("quiet", "CLIENT", "QUIET", "test");
            service.list(quiet);
            service.processNext();
            assertEquals("EMPTY", object(service.list(quiet).get("job")).get("status"));
            assertEquals(0, model.calls.get());
        } finally {
            db.update("DELETE FROM insight_jobs WHERE company_id='QUIET'");
            db.update("DELETE FROM companies WHERE id='QUIET'");
        }
        model.mode = "EMPTY";
        service.list(STABLE);
        service.processNext();
        assertEquals("EMPTY", object(service.list(STABLE).get("job")).get("status"));
        assertTrue(service.notifications(STABLE).isEmpty());
    }

    @Test
    void dataChangesCreateANewJobAndOldCardsBecomeStale() {
        service.list(STABLE);
        service.processNext();
        String id = (String) cards(service.list(STABLE)).getFirst().get("id");
        db.update("UPDATE companies SET balance=balance+1 WHERE id='STABLE'");
        try {
            assertTrue((Boolean) service.detail(STABLE, id).get("stale"));
            assertEquals("QUEUED", object(service.list(STABLE).get("job")).get("status"));
            assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM insight_jobs", Integer.class));
        } finally {
            db.update("UPDATE companies SET balance=balance-1 WHERE id='STABLE'");
        }
    }

    @Test
    void ruleOnlyModeAndCompanyIsolation() {
        model.enabled = false;
        service.list(GAP);
        service.processNext();
        var result = service.list(GAP);
        assertEquals("READY", object(result.get("job")).get("status"));
        var gap = cards(result).stream().filter(c -> c.get("purpose").equals("CASH_GAP")).findFirst().orElseThrow();
        assertEquals("CALCULATED", gap.get("status"));
        assertEquals("RULES", gap.get("explanation_source"));
        String id = (String) gap.get("id");
        assertEquals(DomainException.Kind.NOT_FOUND,
            assertThrows(DomainException.class, () -> service.detail(STABLE, id)).kind);
        assertThrows(DomainException.class, () -> service.read(STABLE, id));
        assertTrue(service.notifications(STABLE).isEmpty());
        assertEquals(0, model.calls.get());
    }

    @Test
    void staleRunningJobsAreRecovered() {
        var job = object(service.list(STABLE).get("job"));
        db.update("UPDATE insight_jobs SET status='RUNNING',lease_token='old',updated_at=? WHERE id=?",
            java.time.LocalDateTime.now().minusMinutes(10), job.get("id"));
        service.processNext();
        assertEquals("READY", object(service.list(STABLE).get("job")).get("status"));
    }

    @Test
    void apiRequiresSessionAndCsrfForMutations() throws Exception {
        mvc.perform(get("/api/v1/hypotheses")).andExpect(status().isUnauthorized());
        var response = mvc.perform(post("/api/v1/auth/sessions").contentType("application/json")
            .content("{\"login\":\"stable\",\"password\":\"Alfa2026!\"}"))
            .andExpect(status().isOk()).andReturn().getResponse();
        var cookie = response.getCookie("cabinet_session");
        String csrf = (String) json.read(response.getContentAsString()).get("csrf");
        mvc.perform(get("/api/v1/hypotheses").cookie(cookie)).andExpect(status().isOk())
            .andExpect(jsonPath("$.job.status").value("QUEUED"));
        mvc.perform(post("/api/v1/hypotheses/refresh").cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/hypotheses/refresh").cookie(cookie).header("X-CSRF-Token", csrf))
            .andExpect(status().isAccepted());
    }
}
