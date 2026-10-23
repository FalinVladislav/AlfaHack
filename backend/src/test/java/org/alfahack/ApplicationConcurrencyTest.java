package org.alfahack;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.alfahack.core.domain.User;
import org.alfahack.core.domain.Util;
import org.alfahack.core.errors.DomainException;
import org.alfahack.core.config.ApplicationRecovery;
import org.alfahack.features.applications.port.ApplicationUseCases;
import org.alfahack.features.drafts.port.DraftUseCases;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow_concurrency;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa", "spring.datasource.password=", "app.demo-today=2026-10-21"
})
class ApplicationConcurrencyTest {

    private final User client = new User("stable", "CLIENT", "STABLE", "test");
    private final User staff = new User("staff", "STAFF", null, "test");
    private final User staff2 = new User("staff2", "STAFF", null, "test");
    @Autowired
    ApplicationUseCases apps;
    @Autowired
    DraftUseCases drafts;
    @Autowired
    JdbcTemplate db;
    @Autowired
    ApplicationRecovery recovery;

    private Map<String, Object> submitted() {
        var draft = drafts.create(client);
        String id = (String) draft.get("id");
        draft = drafts.update(client, id, Map.of("expected_version", draft.get("version"),
            "purpose", "EXPANSION", "amount", "1000000", "term_months", 24, "needed_by", "2026-11-01"));
        var options = (List<Map<String, Object>>) drafts.quote(client, id,
            Map.of("expected_version", draft.get("version"))).get("options");
        var quote = options.stream().filter(item -> "DEVELOPMENT".equals(item.get("product_id"))).findFirst()
            .orElseThrow();
        return apps.submit(client, Map.of("draft_id", id, "quote_id", quote.get("quote_id"),
            "expected_version", draft.get("version"), "consents", true), Util.id());
    }

    private <T> List<Object> race(
        Callable<T> first,
        Callable<T> second
    ) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            Future<Object> a = pool.submit(() -> {
                gate.await();
                return outcome(first);
            });
            Future<Object> b = pool.submit(() -> {
                gate.await();
                return outcome(second);
            });
            gate.countDown();
            return List.of(a.get(), b.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private Object outcome(Callable<?> action) {
        try {
            return action.call();
        } catch (Exception ex) {
            return ex;
        }
    }

    private Map<String, Object> decisionBody(
        Map<String, Object> claimed,
        Map<String, Object> preview
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expected_version", claimed.get("version"));
        body.put("decision_type", "APPROVE");
        body.put("calculation_token", preview.get("calculation_token"));
        body.put("internal_reason", "Финансовые показатели проверены, платёж укладывается в лимит");
        body.put("client_message", "Кредит одобрен на предложенных условиях");
        body.put("expires_at", LocalDateTime.now().plusDays(7).toString());
        return body;
    }

    @Test
    void onlyOneEmployeeCanClaimAnApplication() throws Exception {
        var app = submitted();
        String id = (String) app.get("id");
        int version = ((Number) app.get("version")).intValue();
        var outcomes = race(() -> apps.claim(staff, id, version), () -> apps.claim(staff2, id, version));
        assertEquals(1, outcomes.stream().filter(item -> item instanceof Map).count());
        assertEquals(1, outcomes.stream().filter(item -> item instanceof DomainException).count());
        assertEquals("IN_REVIEW", apps.detail(staff, id).get("status"));
        assertNotNull(apps.detail(staff, id).get("assigned_to"));
    }

    @Test
    void concurrentWithdrawalAndEmployeeDecisionHaveOneOutcome() throws Exception {
        var submitted = submitted();
        String id = (String) submitted.get("id");
        var claimed = apps.claim(staff, id, ((Number) submitted.get("version")).intValue());
        var preview = apps.preview(staff, id, Map.of());
        var body = decisionBody(claimed, preview);
        int version = ((Number) claimed.get("version")).intValue();
        var outcomes = race(() -> apps.withdraw(client, id, version), () -> apps.decide(staff, id, body));
        assertEquals(1, outcomes.stream().filter(item -> item instanceof Map).count());
        assertEquals(1, outcomes.stream().filter(item -> item instanceof DomainException).count());
        assertTrue(List.of("WITHDRAWN", "APPROVED").contains(apps.detail(staff, id).get("status")));
        assertTrue(db.queryForObject("SELECT COUNT(*) FROM bank_decisions WHERE application_id=?",
            Integer.class, id) <= 1);
    }

    @Test
    void signedContractSurvivesFailedCreditAndRetryCreditsExactlyOnce() throws Exception {
        var submitted = submitted();
        String id = (String) submitted.get("id");
        var claimed = apps.claim(staff, id, ((Number) submitted.get("version")).intValue());
        var preview = apps.preview(staff, id, Map.of());
        var decided = apps.decide(staff, id, decisionBody(claimed, preview));
        var offer = (Map<?, ?>) decided.get("offer");
        apps.accept(client, id, Map.of("offer_id", offer.get("id"),
            "offer_version", offer.get("version")), Util.id());
        BigDecimal balance = db.queryForObject("SELECT balance FROM companies WHERE id='STABLE'", BigDecimal.class);
        db.execute("ALTER TABLE companies ADD CONSTRAINT block_credit CHECK (id <> 'STABLE' OR balance <= "
            + balance.toPlainString() + ")");
        try {
            var signed = apps.sign(client, id, Map.of("confirmation", true), Util.id());
            assertEquals("SIGNED", signed.get("status"));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM loans WHERE application_id=?",
                Integer.class, id));
            assertNotNull(((Map<?, ?>) signed.get("contract")).get("signed_at"));
        } finally {
            db.execute("ALTER TABLE companies DROP CONSTRAINT block_credit");
        }
        recovery.run();
        assertEquals("DISBURSED", apps.detail(client, id).get("status"));
        apps.disburse(client, id, Util.id());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM loans WHERE application_id=?",
            Integer.class, id));
    }

    @Test
    void startupRecoveryRescoresAnInterruptedApplication() throws Exception {
        var submitted = submitted();
        String id = (String) submitted.get("id");
        int runs = db.queryForObject("SELECT COUNT(*) FROM scoring_runs WHERE application_id=?",
            Integer.class, id);
        db.update("UPDATE applications SET status='SUBMITTED' WHERE id=?", id);
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM applications WHERE id=? AND status='SUBMITTED'",
            Integer.class, id));
        var candidates = db.queryForList("SELECT a.id FROM applications a LEFT JOIN scoring_reports s "
            + "ON s.application_id=a.id WHERE a.status IN ('SUBMITTED','CHECKING') OR "
            + "(a.status='AWAITING_DECISION' AND a.demo_profile IS NOT NULL AND s.assessed_at IS NULL)");
        assertTrue(candidates.stream().anyMatch(row -> id.equals(row.get("id"))), candidates.toString());
        recovery.run();
        assertEquals("AWAITING_DECISION", apps.detail(staff, id).get("status"));
        assertEquals(runs + 1, db.queryForObject("SELECT COUNT(*) FROM scoring_runs WHERE application_id=?",
            Integer.class, id));
    }
}
