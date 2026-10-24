package org.alfahack;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.alfahack.core.domain.User;
import org.alfahack.core.errors.DomainException;
import org.alfahack.features.products.port.FinancingLimitUseCases;
import org.alfahack.features.drafts.port.DraftUseCases;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:limits;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa", "spring.datasource.password=", "app.insights.worker-enabled=false"})
@Transactional
@AutoConfigureMockMvc
class FinancingLimitsIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    FinancingLimitUseCases limits;
    @Autowired
    DraftUseCases drafts;
    @Autowired
    JdbcTemplate db;
    static final User STABLE = new User("stable", "CLIENT", "STABLE", "test");
    static final LocalDate ISSUE = LocalDate.now(ZoneId.of("Europe/Moscow")).plusDays(10);

    @Autowired
    org.alfahack.core.serialization.JsonCodec json;

    @Test
    void dateBeforeTheCurrentBankDayIsRejected() {
        String yesterday = LocalDate.now(ZoneId.of("Europe/Moscow")).minusDays(1).toString();
        assertEquals(DomainException.Kind.INVALID_INPUT,
            assertThrows(DomainException.class,
                () -> limits.limits(STABLE, "EXPANSION", null, LocalDate.parse(yesterday))).kind);
        var draft = drafts.create(STABLE);
        assertEquals(DomainException.Kind.INVALID_INPUT,
            assertThrows(DomainException.class, () -> drafts.update(STABLE, draft.get("id").toString(),
                Map.of("expected_version", draft.get("version"), "needed_by", yesterday))).kind);
    }

    @Test
    void scenarioEndpointChecksSessionCsrfOwnershipAndQuoteVersion() throws Exception {
        var draft = drafts.create(STABLE);
        String id = draft.get("id").toString();
        draft = drafts.update(STABLE, id, Map.of("expected_version", draft.get("version"), "purpose", "EXPANSION",
            "amount", "1000000", "term_months", 24, "needed_by", ISSUE.toString()));
        var quotes = drafts.quote(STABLE, id, Map.of("expected_version", draft.get("version")));
        String qid = list(quotes, "options").stream().filter(o -> o.get("quote_id") != null).findFirst().orElseThrow()
            .get("quote_id").toString();
        String path = "/api/v1/quotes/" + qid + "/scenarios";
        mvc.perform(post(path).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        var login = mvc.perform(post("/api/v1/auth/sessions").contentType("application/json")
            .content("{\"login\":\"stable\",\"password\":\"Alfa2026!\"}")).andReturn().getResponse();
        var cookie = login.getCookie("cabinet_session");
        var csrf = json.read(login.getContentAsString()).get("csrf");
        mvc.perform(post(path).cookie(cookie).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden());
        mvc.perform(
            post(path).cookie(cookie).header("X-CSRF-Token", csrf).contentType("application/json").content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.start_date").value(LocalDate.now(ZoneId.of("Europe/Moscow")).toString()))
            .andExpect(jsonPath("$.quote_id").value(qid)).andExpect(jsonPath("$.scenarios.length()").value(3))
            .andExpect(jsonPath("$.project_effect_known").value(false));
        var otherLogin = mvc.perform(post("/api/v1/auth/sessions").contentType("application/json")
            .content("{\"login\":\"gap\",\"password\":\"Alfa2026!\"}")).andReturn().getResponse();
        mvc.perform(post(path).cookie(otherLogin.getCookie("cabinet_session"))
            .header("X-CSRF-Token", json.read(otherLogin.getContentAsString()).get("csrf"))
            .contentType("application/json").content("{}")).andExpect(status().isNotFound());
        drafts.update(STABLE, id, Map.of("expected_version", draft.get("version"), "amount", "1100000"));
        mvc.perform(
            post(path).cookie(cookie).header("X-CSRF-Token", csrf).contentType("application/json").content("{}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QUOTE_STALE"));
    }

    @Test
    void httpAcceptsIsoDatesAndUsesTheAuthenticatedCompany() throws Exception {
        mvc.perform(get("/api/v1/financing/limits").param("purpose", "EXPANSION"))
            .andExpect(status().isUnauthorized());
        var response = mvc.perform(post("/api/v1/auth/sessions").contentType("application/json")
            .content("{\"login\":\"stable\",\"password\":\"Alfa2026!\"}"))
            .andExpect(status().isOk()).andReturn().getResponse();
        mvc.perform(get("/api/v1/financing/limits").cookie(response.getCookie("cabinet_session"))
            .param("purpose", "EXPANSION").param("product_id", "DEVELOPMENT_RATE").param("needed_by", ISSUE.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.products[0].product_id").value("DEVELOPMENT_RATE"));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(
        Map<String, Object> data,
        String key
    ) {
        return (List<Map<String, Object>>) data.get(key);
    }

    static BigDecimal max(Map<String, Object> data) {
        return list(data, "products").stream().flatMap(p -> list(p, "terms").stream())
            .map(t -> new BigDecimal(t.get("max_amount").toString())).max(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO);
    }

    @Test
    void selectedProductHasItsOwnBounds() {
        var result = limits.limits(STABLE, "EXPANSION", "DEVELOPMENT_RATE", ISSUE);
        assertEquals("AVAILABLE", result.get("status"));
        assertEquals(1, list(result, "products").size());
        var terms = list(list(result, "products").getFirst(), "terms");
        assertEquals(24, terms.getFirst().get("term"));
        assertEquals(48, terms.getLast().get("term"));
        assertEquals(0, new BigDecimal("500000").compareTo((BigDecimal) terms.getFirst().get("min_amount")));
        assertTrue(max(result).compareTo(new BigDecimal("3000000")) <= 0);
        assertThrows(DomainException.class, () -> limits.limits(STABLE, "CASH_GAP", "DEVELOPMENT_RATE", ISSUE));
    }

    @Test
    void lowerTurnoverAndMoreDebtReduceTheRange() {
        BigDecimal before = max(limits.limits(STABLE, "EXPANSION", null, ISSUE));
        db.update("UPDATE transactions SET amount=amount*0.5 WHERE company_id='STABLE' AND type='INCOME'");
        BigDecimal after = max(limits.limits(STABLE, "EXPANSION", null, ISSUE));
        assertTrue(after.compareTo(before) < 0);
        db.update("UPDATE existing_loans SET monthly_payment=1000000 WHERE company_id='STABLE'");
        assertEquals("NO_CAPACITY", limits.limits(STABLE, "EXPANSION", null, ISSUE).get("status"));
    }

    @Test
    void ageRestrictsTermsAndMissingHistoryDoesNotInventCapacity() {
        db.update("UPDATE companies SET business_age=6 WHERE id='STABLE'");
        var result = limits.limits(STABLE, "EXPANSION", "DEVELOPMENT", ISSUE);
        assertTrue(list(list(result, "products").getFirst(), "terms").stream()
            .allMatch(t -> ((Number) t.get("term")).intValue() <= 24));
        db.update("DELETE FROM transactions WHERE company_id='STABLE'");
        assertEquals("INSUFFICIENT_DATA", limits.limits(STABLE, "EXPANSION", null, ISSUE).get("status"));
    }

    @Test
    void cashGapTermsExcludeDatesWithInsufficientRepaymentFunds() {
        var gap = new User("gap", "CLIENT", "GAP", "test");
        db.update("INSERT INTO ledger_events(id,company_id,event_date,amount,kind,source) VALUES (?,?,?,?,?,?)",
            java.util.UUID.randomUUID().toString(), "GAP", ISSUE.plusDays(24), new BigDecimal("-100000000"),
            "PLANNED", "Future supplier payment");
        var result = limits.limits(gap, "CASH_GAP", null, ISSUE);
        var terms = list(list(result, "products").getFirst(), "terms");
        assertTrue(terms.stream().anyMatch(t -> ((Number) t.get("term")).intValue() == 5));
        assertFalse(terms.stream().anyMatch(t -> ((Number) t.get("term")).intValue() == 24));
    }

    @Test
    void quoteApiNeverCreatesQuotesOutsidePersonalBounds() {
        var draft = drafts.create(STABLE);
        draft = drafts.update(STABLE, (String) draft.get("id"), Map.of("expected_version", draft.get("version"),
            "purpose", "EXPANSION", "amount", "8000000", "term_months", 12, "needed_by", ISSUE.toString()));
        var quote = drafts.quote(STABLE, (String) draft.get("id"), Map.of("expected_version", draft.get("version")));
        assertTrue(list(quote, "options").stream().noneMatch(o -> o.containsKey("quote_id")));
        assertTrue(list(quote, "options").stream().anyMatch(o -> "PERSONAL_LIMIT".equals(o.get("reason_code"))));
        assertEquals(0,
            db.queryForObject("SELECT COUNT(*) FROM quotes WHERE draft_id=?", Integer.class, draft.get("id")));
    }

    @Test
    void staffCannotReadClientLimitsAndDatesAreBounded() {
        assertThrows(DomainException.class,
            () -> limits.limits(new User("staff", "STAFF", null, "test"), "EXPANSION", null, ISSUE));
        assertThrows(DomainException.class, () -> limits.limits(STABLE, "EXPANSION", null, ISSUE.plusYears(1)));
    }
}
