package org.alfahack;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
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
    static final LocalDate ISSUE = LocalDate.of(2026, 10, 22);

    @Test
    void httpAcceptsIsoDatesAndUsesTheAuthenticatedCompany() throws Exception {
        mvc.perform(get("/api/v1/financing/limits").param("purpose", "EXPANSION"))
            .andExpect(status().isUnauthorized());
        var response = mvc.perform(post("/api/v1/auth/sessions").contentType("application/json")
            .content("{\"login\":\"stable\",\"password\":\"Alfa2026!\"}"))
            .andExpect(status().isOk()).andReturn().getResponse();
        mvc.perform(get("/api/v1/financing/limits").cookie(response.getCookie("cabinet_session"))
            .param("purpose", "EXPANSION").param("product_id", "DEVELOPMENT_RATE").param("needed_by", "2026-11-01"))
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
        var result = limits.limits(gap, "CASH_GAP", null, ISSUE);
        var terms = list(list(result, "products").getFirst(), "terms");
        assertTrue(terms.stream().anyMatch(t -> ((Number) t.get("term")).intValue() == 5));
        // Seeded supplier payment makes the baseline negative on demo day +25.
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
