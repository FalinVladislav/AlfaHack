package org.alfahack;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.alfahack.core.domain.*;
import org.alfahack.core.errors.DomainException;
import org.alfahack.features.applications.port.ApplicationUseCases;
import org.alfahack.features.drafts.port.DraftUseCases;
import org.alfahack.features.forecast.port.ForecastUseCases;
import org.alfahack.features.documents.port.DocumentUseCases;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:conveyor;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa", "spring.datasource.password="})
@Transactional
class ConveyorIntegrationTest {

    @Autowired
    ApplicationUseCases apps;
    @Autowired
    DraftUseCases drafts;
    @Autowired
    ForecastUseCases forecast;
    @Autowired
    DocumentUseCases documents;
    @Autowired
    JdbcTemplate db;
    User user(String id) {
        return new User(id.toLowerCase(), "CLIENT", id, "test");
    }
    User staff = new User("staff", "STAFF", null, "test");
    Map<String, Object> quote(
        User user,
        String amount
    ) {
        var draft = drafts.create(user);
        draft = drafts.update(user, (String) draft.get("id"), Map.of("expected_version", draft.get("version"),
            "purpose", "EXPANSION", "amount", amount, "term_months", 24, "needed_by", "2026-11-01"));
        var result = drafts.quote(user, (String) draft.get("id"), Map.of("expected_version", draft.get("version")));
        return drafts.quoteById(user, (String) list(result, "options").stream()
            .filter(q -> "DEVELOPMENT".equals(q.get("product_id"))).findFirst().orElseThrow().get("quote_id"));
    }

    Map<String, Object> submit(User user) {
        var quote = quote(user, "1000000");
        return apps.submit(user, Map.of("draft_id", quote.get("draft_id"), "quote_id", quote.get("id"),
            "expected_version", quote.get("draft_version"), "consents", true), UUID.randomUUID().toString());
    }

    @Test
    void allFourOutcomesAreCompanyBoundAndAutomatic() {
        for (var item : Map
            .of("STABLE", "APPROVED", "REJECT", "REJECTED", "MISSING", "NEEDS_INFO", "COUNTER", "COUNTEROFFER")
            .entrySet()) {
            var result = submit(user(item.getKey()));
            assertEquals(item.getValue(), result.get("status"), item.getKey());
            String id = (String) result.get("id");
            assertEquals("PROFILE_LOCKED", assertThrows(DomainException.class, () -> apps.check(staff, id, "IT")).code);
            var before = apps.detail(staff, id);
            if (!"NEEDS_INFO".equals(result.get("status"))) {
                var repeated = apps.check(staff, id, null);
                assertEquals(before.get("events"), repeated.get("events"));
                assertEquals(before.get("decision_report"), repeated.get("decision_report"));
            }
            assertTrue(list(result, "events").size() >= 3);
        }
    }

    @Test
    void submissionAndDisbursementAreIdempotentAndPrivate() {
        User stable = user("STABLE");
        var q = quote(stable, "1000000");
        var body = Map.<String, Object>of("draft_id", q.get("draft_id"), "quote_id", q.get("id"), "expected_version",
            q.get("draft_version"), "consents", true);
        var a = apps.submit(stable, body, "same");
        String id = (String) a.get("id");
        assertEquals(id, apps.submit(stable, body, "same").get("id"));
        assertThrows(DomainException.class, () -> apps.detail(user("GAP"), id));
        apps.accept(stable, id, Map.of("offer_version", a.get("offer_version")), "accept");
        apps.sign(stable, id, Map.of("confirmation", true), "sign");
        apps.disburse(stable, id, "issue");
        apps.disburse(stable, id, "issue");
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM loans WHERE application_id=?", Integer.class, id));
        assertEquals("DISBURSED", apps.detail(stable, id).get("status"));
        forecast.forecast(stable, "30D", "BASE"); // newly issued schedule must also be readable
    }

    @Test
    void verifiedDocumentResumesTheSameCompany() throws Exception {
        var user = user("MISSING");
        var a = submit(user);
        String id = (String) a.get("id");
        String request = (String) list(a, "requests").getFirst().get("id");
        byte[] bytes = getClass().getResourceAsStream("/seed-documents/missing_statement.csv").readAllBytes();
        documents.upload(user, id, request, new UploadedDocument("statement.csv", bytes));
        assertEquals("APPROVED", documents.complete(user, id, request).get("status"));
    }

    @Test
    void dailyAnalysisUsesInvestmentSpendAndFullTermPayments() {
        var user = user("STABLE");
        var q = quote(user, "1000000");
        var a = forecast.analysis(user, q, "FULL", "BASE");
        var without = list(a, "without_credit");
        var with = list(a, "with_credit");
        assertTrue(with.size() > 700);
        assertEquals(24, list(a, "payments").size());
        int issue = java.util.stream.IntStream.range(0, with.size())
            .filter(i -> with.get(i).get("date").toString().equals("2026-11-01")).findFirst().orElseThrow();
        assertEquals(without.get(issue).get("balance"), with.get(issue).get("balance"));
        assertEquals(with.stream().map(p -> (BigDecimal) p.get("balance")).min(BigDecimal::compareTo).orElseThrow(),
            a.get("minimum_with"));
        assertNotEquals(forecast.forecast(user, "30D", "BASE").get("points"),
            forecast.forecast(user, "30D", "STRESS").get("points"));
    }

    @Test
    void exactSchedulesAndInvestmentIssueDoNotCreateProfit() {
        var catalog = new org.alfahack.features.products.application.Products();
        var amount = new BigDecimal("1234567.89");
        var calc = catalog.calculate(catalog.get("DEVELOPMENT"), amount, 36, null);
        assertEquals(0, list(calc, "schedule").stream().map(p -> (BigDecimal) p.get("principal"))
            .reduce(BigDecimal.ZERO, BigDecimal::add).compareTo(amount));
        assertEquals(0, ((BigDecimal) list(calc, "schedule").getLast().get("remaining")).signum());
        var overdraft = catalog.calculate(catalog.get("OVERDRAFT"), new BigDecimal("100000"), null, 45);
        assertEquals(1, list(overdraft, "schedule").size());
        assertEquals(new BigDecimal("2958.90"), overdraft.get("interest"));
        var client = user("STABLE");
        BigDecimal before = db.queryForObject("SELECT balance FROM companies WHERE id='STABLE'", BigDecimal.class);
        var app = submit(client);
        String id = (String) app.get("id");
        apps.accept(client, id, Map.of("offer_version", app.get("offer_version")), "accept-investment");
        apps.sign(client, id, Map.of("confirmation", true), "sign-investment");
        apps.disburse(client, id, "issue-investment");
        assertEquals(before, db.queryForObject("SELECT balance FROM companies WHERE id='STABLE'", BigDecimal.class));
        assertEquals(1, db.queryForObject(
            "SELECT COUNT(*) FROM ledger_events WHERE company_id='STABLE' AND kind='INVESTMENT_SPEND'", Integer.class));
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> list(
        Map<String, Object> m,
        String key
    ) {
        return (List<Map<String, Object>>) m.get(key);
    }
}
