package org.alfahack;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.alfahack.core.domain.User;
import org.alfahack.core.domain.Util;
import org.alfahack.core.errors.DomainException;
import org.alfahack.features.applications.port.ApplicationUseCases;
import org.alfahack.features.drafts.port.DraftUseCases;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa", "spring.datasource.password=", "app.demo-today=2026-10-21"
})
@Transactional
class ApplicationWorkflowIntegrationTest {

    private final User client = new User("stable", "CLIENT", "STABLE", "test");
    private final User otherClient = new User("gap", "CLIENT", "GAP", "test");
    private final User staff = new User("staff", "STAFF", null, "test");
    private final User otherStaff = new User("staff2", "STAFF", null, "test");
    @Autowired
    ApplicationUseCases apps;
    @Autowired
    DraftUseCases drafts;
    @Autowired
    JdbcTemplate db;

    private Map<String, Object> submit(String amount) {
        var draft = drafts.create(client);
        String id = (String) draft.get("id");
        draft = drafts.update(client, id, Map.of("expected_version", draft.get("version"),
            "purpose", "EXPANSION", "amount", amount, "term_months", 24, "needed_by", "2026-11-01"));
        var quotes = drafts.quote(client, id, Map.of("expected_version", draft.get("version")));
        var options = (List<Map<String, Object>>) quotes.get("options");
        var quote = options.stream().filter(item -> "DEVELOPMENT".equals(item.get("product_id"))).findFirst()
            .orElseThrow();
        return apps.submit(client, Map.of("draft_id", id, "quote_id", quote.get("quote_id"),
            "expected_version", draft.get("version"), "consents", true), Util.id());
    }

    private Map<String, Object> claim(Map<String, Object> application) {
        return apps.claim(staff, (String) application.get("id"), ((Number) application.get("version")).intValue());
    }

    private Map<String, Object> decisionBody(
        Map<String, Object> claimed,
        Map<String, Object> preview,
        String type
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expected_version", claimed.get("version"));
        body.put("decision_type", type);
        body.put("calculation_token", preview.get("calculation_token"));
        body.put("amount", preview.get("amount"));
        body.put("term_months", preview.get("term_months"));
        body.put("internal_reason", "Проверены денежный поток, нагрузка и условия продукта");
        body.put("client_message", "Решение по вашей заявке доступно в кабинете");
        body.put("expires_at", LocalDateTime.now().plusDays(7).toString());
        return body;
    }

    @Test
    void positiveRecommendationNeedsEmployeeThenClientAcceptanceAndOneDisbursement() {
        var submitted = submit("1000000");
        String id = (String) submitted.get("id");
        assertEquals("AWAITING_DECISION", submitted.get("status"));
        assertNull(submitted.get("offer"));
        assertNotNull(apps.detail(staff, id).get("decision_report"));
        var claimed = claim(submitted);
        assertThrows(DomainException.class, () -> apps.decide(otherStaff, id, Map.of()));
        var preview = apps.preview(staff, id, Map.of());
        var decided = apps.decide(staff, id, decisionBody(claimed, preview, "APPROVE"));
        assertEquals("APPROVED", decided.get("status"));
        assertEquals(0, ((BigDecimal) ((Map<?, ?>) decided.get("request")).get("amount"))
            .compareTo(new BigDecimal("1000000")));
        var offer = (Map<?, ?>) decided.get("offer");
        var accepted = apps.accept(client, id, Map.of("offer_id", offer.get("id"),
            "offer_version", offer.get("version")), Util.id());
        assertEquals("OFFER_ACCEPTED", accepted.get("status"));
        String signingKey = Util.id();
        var signed = apps.sign(client, id, Map.of("confirmation", true), signingKey);
        assertEquals("DISBURSED", signed.get("status"));
        apps.sign(client, id, Map.of("confirmation", true), signingKey);
        apps.disburse(client, id, Util.id());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM loans WHERE application_id=?", Integer.class, id));
        assertEquals(1, db.queryForObject(
            "SELECT COUNT(*) FROM ledger_events WHERE kind='DISBURSEMENT' AND source IN (SELECT CONCAT('Выдача кредита ',id) FROM loans WHERE application_id=?)",
            Integer.class, id));
        assertEquals(24, ((List<?>) apps.detail(client, id).get("schedule")).size());
        assertEquals("DISBURSED", apps.detail(client, id).get("status"));
    }

    @Test
    void negativeRecommendationCanBeOverriddenOnlyWithMeaningfulExplanation() {
        db.update("UPDATE company_risk_facts SET overdue_days=45,credit_history='OVERDUE' WHERE company_id='STABLE'");
        var submitted = submit("1000000");
        String id = (String) submitted.get("id");
        var claimed = claim(submitted);
        var preview = apps.preview(staff, id, Map.of());
        assertEquals("REJECTED", ((Map<?, ?>) preview.get("report")).get("outcome"));
        var body = decisionBody(claimed, preview, "APPROVE");
        assertThrows(DomainException.class, () -> apps.decide(staff, id, body));
        body.put("divergence_reason", "Подтверждённый источник погашения покрывает просрочку и будущие платежи");
        assertEquals("APPROVED", apps.decide(staff, id, body).get("status"));
        assertNotNull(((Map<?, ?>) apps.detail(staff, id).get("bank_decision")).get("divergence_reason"));
    }

    @Test
    void positiveRecommendationMayBeRejectedOnlyWithExplanationAndNoInternalLeak() {
        db.update(
            "UPDATE company_risk_facts SET current_assets=2000000,collateral_value=3000000 WHERE company_id='STABLE'");
        var submitted = submit("1000000");
        String id = (String) submitted.get("id");
        var claimed = claim(submitted);
        var preview = apps.preview(staff, id, Map.of());
        assertEquals("APPROVED", ((Map<?, ?>) preview.get("report")).get("outcome"));
        var body = decisionBody(claimed, preview, "REJECT");
        body.put("reason", "Сведения требуют дополнительной проверки: ".repeat(4));
        assertThrows(DomainException.class, () -> apps.decide(staff, id, body));
        body.put("divergence_reason", "Выявлено несоответствие в предоставленных договорных документах");
        assertEquals("REJECTED", apps.decide(staff, id, body).get("status"));
        var visible = (Map<?, ?>) apps.detail(client, id).get("bank_decision");
        assertFalse(visible.containsKey("internal_reason"));
        assertFalse(visible.containsKey("divergence_reason"));
        assertFalse(visible.containsKey("scoring_snapshot"));
        assertFalse(visible.containsKey("reason"));
        assertNull(apps.detail(client, id).get("decision_report"));
    }

    @Test
    void counterofferRecalculatesAndPreservesOriginalRequestAndRejectsStaleVersion() {
        var submitted = submit("1000000");
        String id = (String) submitted.get("id");
        var claimed = claim(submitted);
        var original = apps.preview(staff, id, Map.of());
        var revised = apps.preview(staff, id, Map.of("amount", "600000", "term_months", 36));
        assertNotEquals(original.get("calculation_token"), revised.get("calculation_token"));
        var body = decisionBody(claimed, revised, "COUNTEROFFER");
        body.put("reason", "Уменьшение платежа соответствует текущему денежному потоку");
        var decided = apps.decide(staff, id, body);
        assertEquals("COUNTEROFFER", decided.get("status"));
        assertEquals(0, ((BigDecimal) ((Map<?, ?>) decided.get("request")).get("amount"))
            .compareTo(new BigDecimal("1000000")));
        var offer = (Map<?, ?>) decided.get("offer");
        assertEquals(0, ((BigDecimal) offer.get("amount")).compareTo(new BigDecimal("600000")));
        assertThrows(DomainException.class, () -> apps.accept(client, id,
            Map.of("offer_id", offer.get("id"), "offer_version", 999), Util.id()));
        apps.accept(client, id, Map.of("offer_id", offer.get("id"),
            "offer_version", offer.get("version")), Util.id());
        var copied = apps.copyToDraft(client, id);
        assertEquals("ACTIVE", copied.get("status"));
        assertEquals(0, ((BigDecimal) copied.get("amount")).compareTo(new BigDecimal("1000000")));
    }

    @Test
    void withdrawalBlocksClaimAndOriginalDraftIsImmutable() {
        var submitted = submit("1000000");
        String id = (String) submitted.get("id");
        assertEquals("WITHDRAWN", apps.withdraw(client, id, ((Number) submitted.get("version")).intValue())
            .get("status"));
        assertThrows(DomainException.class, () -> apps.claim(staff, id,
            ((Number) submitted.get("version")).intValue()));
        var originalDraft = drafts.get(client, (String) submitted.get("draft_id"));
        assertThrows(DomainException.class, () -> drafts.update(client, (String) originalDraft.get("id"),
            Map.of("expected_version", originalDraft.get("version"), "amount", "2000000")));
    }

    @Test
    void staleCalculationAndRepeatDecisionAreRejected() {
        var submitted = submit("1000000");
        String id = (String) submitted.get("id");
        var claimed = claim(submitted);
        var preview = apps.preview(staff, id, Map.of());
        db.update("UPDATE company_risk_facts SET current_assets=current_assets/3 WHERE company_id='STABLE'");
        var body = decisionBody(claimed, preview, "APPROVE");
        assertThrows(DomainException.class, () -> apps.decide(staff, id, body));
        db.update("UPDATE company_risk_facts SET current_assets=current_assets*3 WHERE company_id='STABLE'");
        var fresh = apps.preview(staff, id, Map.of());
        var freshBody = decisionBody(claimed, fresh, "APPROVE");
        apps.decide(staff, id, freshBody);
        Map<String, Object> again = freshBody;
        assertThrows(DomainException.class, () -> apps.decide(staff, id, again));
    }

    @Test
    void crossClientAccessAndUnknownNotificationAreIsolated() {
        var submitted = submit("1000000");
        String id = (String) submitted.get("id");
        assertThrows(DomainException.class, () -> apps.detail(otherClient, id));
        assertThrows(DomainException.class, () -> apps.copyToDraft(otherClient, id));
        assertThrows(DomainException.class, () -> apps.accept(otherClient, id,
            Map.of("offer_id", Util.id(), "offer_version", 1), Util.id()));
        assertTrue(apps.notifications(otherClient).isEmpty());
    }

    @Test
    void offerExpiryIsCheckedByServer() throws InterruptedException {
        var submitted = submit("1000000");
        String id = (String) submitted.get("id");
        var claimed = claim(submitted);
        var preview = apps.preview(staff, id, Map.of());
        var body = decisionBody(claimed, preview, "APPROVE");
        body.put("expires_at", LocalDateTime.now().plusSeconds(1).toString());
        var decided = apps.decide(staff, id, body);
        Thread.sleep(1200);
        var offer = (Map<?, ?>) decided.get("offer");
        assertThrows(DomainException.class, () -> apps.accept(client, id,
            Map.of("offer_id", offer.get("id"), "offer_version", offer.get("version")), Util.id()));
        assertEquals("EXPIRED", apps.detail(client, id).get("status"));
    }

    @Test
    void clientDeclinesOfferAndNotificationReadIsSeparateFromCreation() {
        var submitted = submit("1000000");
        String id = (String) submitted.get("id");
        var claimed = claim(submitted);
        var preview = apps.preview(staff, id, Map.of());
        apps.decide(staff, id, decisionBody(claimed, preview, "APPROVE"));
        var notices = apps.notifications(client);
        var notice = notices.stream().filter(row -> id.equals(row.get("application_id")))
            .findFirst().orElseThrow();
        assertNull(notice.get("read_at"));
        apps.readNotification(client, (String) notice.get("id"));
        assertNotNull(apps.notifications(client).stream()
            .filter(row -> id.equals(row.get("application_id"))).findFirst().orElseThrow().get("read_at"));
        assertEquals("CLOSED", apps.decline(client, id).get("status"));
        assertThrows(DomainException.class, () -> apps.accept(client, id,
            Map.of("offer_id", ((Map<?, ?>) apps.detail(client, id).get("offer")).get("id"),
                "offer_version", 1),
            Util.id()));
        assertNull(apps.detail(client, id).get("contract"));
    }

    @Test
    void scoringSourceFailureCanBeRetriedWithoutAutomaticRejection() {
        db.update("UPDATE company_risk_facts SET credit_history='SOURCE_UNAVAILABLE' WHERE company_id='STABLE'");
        var submitted = submit("1000000");
        String id = (String) submitted.get("id");
        assertEquals("SCORING_ERROR", submitted.get("status"));
        assertNull(submitted.get("offer"));
        db.update("UPDATE company_risk_facts SET credit_history='CLEAN' WHERE company_id='STABLE'");
        assertEquals("AWAITING_DECISION", apps.check(staff, id, null).get("status"));
        assertNull(apps.detail(staff, id).get("bank_decision"));
    }
}
