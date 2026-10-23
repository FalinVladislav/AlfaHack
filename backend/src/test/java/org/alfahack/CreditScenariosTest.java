package org.alfahack;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.alfahack.core.domain.User;
import org.alfahack.core.errors.DomainException;
import org.alfahack.features.finance.port.FinanceUseCases;
import org.alfahack.features.forecast.port.ForecastRepository;
import org.alfahack.features.forecast.application.CreditScenarios;
import org.alfahack.features.products.application.Products;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CreditScenariosTest {

    final User user = new User("test", "CLIENT", "STABLE", "csrf");
    final LocalDate today = LocalDate.of(2026, 10, 21);
    final ForecastRepository repo = mock(ForecastRepository.class);
    final FinanceUseCases finance = mock(FinanceUseCases.class);
    final Products products = new Products();
    final Map<String, Object> draft = new HashMap<>(Map.of("purpose", "EXPANSION", "version", 1,
        "needed_by", today.plusDays(1), "goal_text", "Оборудование"));
    final Map<String, Object> quote = new HashMap<>(Map.of("id", "q", "draft_id", "d", "draft_version", 1,
        "product_id", "DEVELOPMENT", "amount", new BigDecimal("1200000"), "term_months", 12));
    final CreditScenarios engine = new CreditScenarios(finance, repo, products, today.toString());

    @BeforeEach
    void fixture() {
        when(repo.draft("d")).thenReturn(draft);
        when(repo.balance("STABLE")).thenReturn(Map.of("balance", new BigDecimal("500000")));
        when(repo.plannedEvents(eq("STABLE"), any(), any())).thenReturn(List.of());
        when(finance.overview(user)).thenReturn(Map.of("months", List.of(
            Map.of("month", "2026-07", "income", 1000000, "expenses", 700000),
            Map.of("month", "2026-08", "income", 1000000, "expenses", 700000),
            Map.of("month", "2026-09", "income", 1000000, "expenses", 700000)), "loans", List.of(), "data_origin",
            "SYNTHETIC"));
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> outcomes(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("scenarios");
    }

    BigDecimal n(
        Map<String, Object> row,
        String key
    ) {
        return (BigDecimal) row.get(key);
    }

    @Test
    void noGrowthIsInventedAndExactFinalPaymentIsIncluded() {
        var result = engine.simulate(user, quote, Map.of());
        assertEquals(false, result.get("project_effect_known"));
        var calculated = products.calculate(products.get("DEVELOPMENT"), new BigDecimal("1200000"), 12, null);
        var list = outcomes(result);
        assertEquals(List.of("STRESS", "BASE", "OPTIMISTIC"), list.stream().map(s -> s.get("id")).toList());
        for (var s : list) {
            assertEquals(0, n(s, "remaining_principal").signum());
            assertEquals(0, n(s, "loan_payments").compareTo((BigDecimal) calculated.get("total_repay")));
            assertEquals(0, n(s, "project_and_credit_delta").compareTo(n(s, "loan_payments").negate()));
        }
        assertTrue(n(list.get(0), "ending_balance").compareTo(n(list.get(1), "ending_balance")) < 0);
        assertTrue(n(list.get(1), "ending_balance").compareTo(n(list.get(2), "ending_balance")) < 0);
    }

    @Test
    void amountAndTermChangePaymentsAndRemainingDebt() {
        var before = outcomes(engine.simulate(user, quote, Map.of())).get(1);
        quote.put("term_months", 24);
        var longer = outcomes(engine.simulate(user, quote, Map.of())).get(1);
        assertTrue(n(longer, "loan_payments").compareTo(n(before, "loan_payments")) < 0);
        assertTrue(n(longer, "remaining_principal").signum() > 0);
        quote.put("amount", new BigDecimal("1500000"));
        var larger = outcomes(engine.simulate(user, quote, Map.of())).get(1);
        assertTrue(n(larger, "loan_payments").compareTo(n(longer, "loan_payments")) > 0);
    }

    @Test
    void projectEconomicsAndDelayAffectOutcomeWithoutScalingWithLoan() {
        var plan = Map.<String, Object>of("project_cost", 1000000, "monthly_revenue", 200000,
            "monthly_expenses", 100000, "delay_months", 2);
        var before = outcomes(engine.simulate(user, quote, plan)).get(1);
        var delayed = new HashMap<>(plan);
        delayed.put("delay_months", 10);
        var after = outcomes(engine.simulate(user, quote, delayed)).get(1);
        assertTrue(n(before, "ending_balance").compareTo(n(after, "ending_balance")) > 0);
        assertEquals(today.plusDays(1).plusMonths(4),
            outcomes(engine.simulate(user, quote, plan)).get(0).get("benefit_start"));
        quote.put("amount", new BigDecimal("2000000"));
        var result = engine.simulate(user, quote, plan);
        assertEquals(true, result.get("project_effect_known"));
        assertEquals(plan.get("monthly_revenue").toString() + ".00",
            ((Map<?, ?>) result.get("inputs")).get("monthly_revenue").toString());
    }

    @Test
    void shortTermCreditIsNotSpentTwiceAndIsFullyRepaid() {
        draft.put("purpose", "CASH_GAP");
        quote.remove("term_months");
        quote.put("duration_days", 30);
        quote.put("product_id", "OVERDRAFT");
        quote.put("amount", new BigDecimal("100000"));
        var s = outcomes(engine.simulate(user, quote, Map.of())).get(1);
        var calculated = products.calculate(products.get("OVERDRAFT"), new BigDecimal("100000"), null, 30);
        assertEquals(0, n(s, "remaining_principal").signum());
        assertEquals(0, n(s, "project_and_credit_delta").compareTo(((BigDecimal) calculated.get("interest")).negate()));
        assertThrows(DomainException.class, () -> engine.simulate(user, quote, Map.of("project_cost", 1000)));
    }

    @Test
    void knownCalendarReplacesBudgetInsteadOfDoublingIt() {
        var before = outcomes(engine.simulate(user, quote, Map.of())).get(1);
        when(repo.plannedEvents(eq("STABLE"), any(), any())).thenReturn(List.of(
            Map.of("event_date", LocalDate.of(2026, 11, 5), "amount", new BigDecimal("100000")),
            Map.of("event_date", LocalDate.of(2026, 11, 10), "amount", new BigDecimal("-100000"))));
        var after = outcomes(engine.simulate(user, quote, Map.of())).get(1);
        assertEquals(0,
            n(before, "without_credit_ending_balance").compareTo(n(after, "without_credit_ending_balance")));
        when(repo.plannedEvents(eq("STABLE"), any(), any())).thenReturn(List.of(
            Map.of("event_date", LocalDate.of(2026, 11, 10), "amount", new BigDecimal("-1700000"))));
        var excess = outcomes(engine.simulate(user, quote, Map.of())).get(1);
        assertEquals(new BigDecimal("1000000.00"),
            n(before, "without_credit_ending_balance").subtract(n(excess, "without_credit_ending_balance")));
    }

    @Test
    void rejectsStaleQuoteAndInvalidOrIncompleteInputs() {
        assertThrows(DomainException.class, () -> engine.simulate(user, quote, Map.of("monthly_revenue", 100)));
        assertThrows(DomainException.class, () -> engine.simulate(user, quote, Map.of("project_cost", -1)));
        assertThrows(DomainException.class, () -> engine.simulate(user, quote, Map.of("project_cost", "NaN")));
        assertThrows(DomainException.class, () -> engine.simulate(user, quote, Map.of("delay_months", 25)));
        draft.put("version", 2);
        assertThrows(DomainException.class, () -> engine.simulate(user, quote, Map.of()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void syntheticOldDebtIsSeparatedAndNewDebtDoesNotReduceHistoricalExpenses() {
        var overview = new HashMap<>(finance.overview(user));
        var existing = Map.<String, Object>of("source", "EXISTING", "monthly_payment", 100000,
            "next_payment_date", today.plusDays(1), "end_date", today.plusMonths(24));
        overview.put("loans", List.of(existing));
        when(finance.overview(user)).thenReturn(overview);
        var result = engine.simulate(user, quote, Map.of());
        assertEquals(new BigDecimal("600000.00"),
            ((Map<String, Object>) result.get("basis")).get("monthly_operating_expenses"));
        var oldBalance = outcomes(result).get(1);
        var issued = new HashMap<>(existing);
        issued.put("source", "ISSUED");
        overview.put("loans", List.of(existing, issued));
        result = engine.simulate(user, quote, Map.of());
        assertEquals(new BigDecimal("600000.00"),
            ((Map<String, Object>) result.get("basis")).get("monthly_operating_expenses"));
        assertEquals(new BigDecimal("1300000.00"), n(oldBalance, "without_credit_ending_balance")
            .subtract(n(outcomes(result).get(1), "without_credit_ending_balance")));
    }

    @Test
    void missingHistoryDoesNotCreateScenarios() {
        when(finance.overview(user)).thenReturn(Map.of("months", List.of()));
        var result = engine.simulate(user, quote, Map.of());
        assertEquals("INSUFFICIENT_DATA", result.get("status"));
        assertTrue(outcomes(result).isEmpty());
    }
}
