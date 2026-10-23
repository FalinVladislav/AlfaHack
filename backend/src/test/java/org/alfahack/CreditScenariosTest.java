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
        // Constant history has no invented volatility floor.
        assertEquals(n(list.get(0), "ending_balance"), n(list.get(1), "ending_balance"));
        assertEquals(n(list.get(1), "ending_balance"), n(list.get(2), "ending_balance"));
    }

    @Test
    void amountAndTermChangePaymentsAndRemainingDebt() {
        var before = outcomes(engine.simulate(user, quote, Map.of())).get(1);
        quote.put("term_months", 24);
        var longer = outcomes(engine.simulate(user, quote, Map.of())).get(1);
        assertTrue(n(longer, "loan_payments").compareTo(n(before, "loan_payments")) > 0);
        assertEquals(0, n(longer, "remaining_principal").signum());
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
        assertTrue(n(BusinessPlanTest.rows(before, "points").get(6), "balance")
            .compareTo(n(BusinessPlanTest.rows(after, "points").get(6), "balance")) > 0);
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

    Map<String, Object> simulatePlan(Map<String, Object> plan) {
        BusinessPlanTest.sourceAll(plan);
        var effects = new org.alfahack.features.scenarios.application.BusinessPlan().evaluate(plan);
        assertEquals("READY", effects.get("status"));
        return engine.simulatePlan(user, quote, effects);
    }

    Map<String, Object> cashPlan(
        double revenue,
        double expenses
    ) {
        var plan = BusinessPlanTest.modernPlan();
        plan.put("investment", BusinessPlanTest.range(0));
        plan.put("launch_months", BusinessPlanTest.range(2));
        plan.put("components", List.of(Map.of("id", "extra", "label", "Дополнительный поток", "kind", "CASHFLOW",
            "resource_ids", List.of(), "revenue", BusinessPlanTest.range(revenue),
            "expenses", BusinessPlanTest.range(expenses), "savings", BusinessPlanTest.range(0), "lost_revenue",
            BusinessPlanTest.range(0))));
        return plan;
    }

    @Test
    void rejectsStaleOrDiscontinuousHistoryAndUsesTrendWithoutArtificialSpread() {
        var overview = new HashMap<>(finance.overview(user));
        overview.put("months", List.of(
            Map.of("month", "2026-06", "income", 1000000, "expenses", 700000),
            Map.of("month", "2026-08", "income", 1000000, "expenses", 700000),
            Map.of("month", "2026-09", "income", 1000000, "expenses", 700000)));
        when(finance.overview(user)).thenReturn(overview);
        assertEquals("INSUFFICIENT_DATA", engine.simulate(user, quote, Map.of()).get("status"));
        overview.put("months", List.of(
            Map.of("month", "2026-04", "income", 1000000, "expenses", 700000),
            Map.of("month", "2026-05", "income", 1000000, "expenses", 700000),
            Map.of("month", "2026-06", "income", 1000000, "expenses", 700000)));
        assertEquals("INSUFFICIENT_DATA", engine.simulate(user, quote, Map.of()).get("status"));
        overview.put("months", List.of(
            Map.of("month", "2026-07", "income", 800000, "expenses", 700000),
            Map.of("month", "2026-08", "income", 900000, "expenses", 700000),
            Map.of("month", "2026-09", "income", 1000000, "expenses", 700000)));
        var growing = engine.simulate(user, quote, Map.of());
        assertEquals(0.0, ((Map<?, ?>) growing.get("basis")).get("revenue_variation"));
        assertTrue(
            n(outcomes(growing).get(1), "without_credit_ending_balance").compareTo(new BigDecimal("10000000")) > 0);
    }

    @Test
    void preparationCostsArePaidBeforeRevenueAndCollectionDelayCreatesReceivables() {
        var plan = cashPlan(100000, 20000);
        var original = outcomes(simulatePlan(plan)).get(1);
        var timing = BusinessPlanTest.params(plan, "cashflow");
        timing.put("prelaunch_monthly_expenses", BusinessPlanTest.range(50000));
        var preparation = outcomes(simulatePlan(plan)).get(1);
        assertTrue(n(preparation, "ending_balance").compareTo(n(original, "ending_balance")) < 0);
        var originalPoints = BusinessPlanTest.rows(original, "points");
        var preparationPoints = BusinessPlanTest.rows(preparation, "points");
        assertTrue(n(preparationPoints.get(1), "balance").compareTo(n(originalPoints.get(1), "balance")) < 0);
        timing.put("collection_delay_days", BusinessPlanTest.range(60));
        var delayed = outcomes(simulatePlan(plan)).get(1);
        assertTrue(n(delayed, "uncollected_project_revenue").signum() > 0);
        var beforeCollection = BusinessPlanTest.rows(preparation, "points").get(4);
        var afterCollection = BusinessPlanTest.rows(delayed, "points").get(4);
        assertTrue(n(afterCollection, "balance").compareTo(n(beforeCollection, "balance")) < 0);
    }

    @Test
    void advancePaymentRampAndExplicitTaxReduceCashInsteadOfChangingSalesIntoCash() {
        var plan = cashPlan(100000, 20000);
        plan.put("launch_months", BusinessPlanTest.range(0));
        var original = outcomes(simulatePlan(plan)).get(1);
        var timing = BusinessPlanTest.params(plan, "cashflow");
        timing.put("expense_advance_days", BusinessPlanTest.range(60));
        timing.put("ramp_months", BusinessPlanTest.range(3));
        timing.put("revenue_tax_rate", BusinessPlanTest.range(.1));
        var adjusted = outcomes(simulatePlan(plan)).get(1);
        assertTrue(n(adjusted, "prepaid_at_issue").signum() > 0);
        // Compare at the same date: the longer ramp extends the reporting horizon.
        var before = BusinessPlanTest.rows(original, "points").get(1);
        var after = BusinessPlanTest.rows(adjusted, "points").get(1);
        assertTrue(n(after, "balance").compareTo(n(before, "balance")) < 0);
    }

    @Test
    void delayedLossMakingProjectIsNotAutomaticallyTheWorstOutcome() {
        var plan = cashPlan(0, 100000);
        plan.put("launch_months", Map.of("low", 0, "base", 2, "high", 4));
        var list = outcomes(simulatePlan(plan));
        assertEquals(0, list.getFirst().get("launch_delay_months"));
        assertEquals(4, list.getLast().get("launch_delay_months"));
        assertTrue(n(list.getFirst(), "ending_balance").compareTo(n(list.get(1), "ending_balance")) < 0);
        assertTrue(n(list.get(1), "ending_balance").compareTo(n(list.getLast(), "ending_balance")) < 0);
    }

    @Test
    void unfundedInvestmentCannotProduceRevenueAndStagesDelayLaunch() {
        var plan = cashPlan(10000000, 0);
        plan.put("investment", BusinessPlanTest.range(3000000));
        var blocked = outcomes(simulatePlan(plan)).get(1);
        assertEquals(false, blocked.get("funding_feasible"));
        assertTrue(n(blocked, "funding_shortfall").signum() > 0);
        assertTrue(n(blocked, "project_and_credit_delta").signum() < 0);
        plan.put("investment", BusinessPlanTest.range(100000));
        plan.put("launch_months", BusinessPlanTest.range(0));
        plan.put("investment_schedule", List.of(Map.of("month", 0, "share", .5), Map.of("month", 3, "share", .5)));
        var staged = outcomes(simulatePlan(plan)).get(1);
        assertEquals(true, staged.get("funding_feasible"));
        assertEquals(today.plusDays(1).plusMonths(3), staged.get("benefit_start"));
    }

    @Test
    void rejectsCurrentChannelsAndSavingsThatExceedHistoricalBusiness() {
        var plan = BusinessPlanTest.modernPlan();
        var before = BusinessPlanTest.params(BusinessPlanTest.rows(plan, "components").getFirst(), "before");
        before.put("price", 1000000);
        assertThrows(DomainException.class, () -> simulatePlan(plan));
        var savings = cashPlan(0, 0);
        savings.put("components", List.of(new HashMap<>(BusinessPlanTest.rows(savings, "components").getFirst())));
        BusinessPlanTest.rows(savings, "components").getFirst().put("savings", BusinessPlanTest.range(800000));
        assertThrows(DomainException.class, () -> simulatePlan(savings));
    }

    @Test
    void exactExistingIssuedScheduleUsesActualFinalPaymentOnce() {
        var original = outcomes(engine.simulate(user, quote, Map.of())).get(1);
        var overview = new HashMap<>(finance.overview(user));
        overview.put("loans", List.of(Map.of("source", "ISSUED", "monthly_payment", 100000,
            "next_payment_date", today.plusDays(1), "schedule", List.of(
                Map.of("due_date", today.plusDays(1), "payment", 100000),
                Map.of("due_date", today.plusMonths(1), "payment", 12345)))));
        when(finance.overview(user)).thenReturn(overview);
        var withDebt = outcomes(engine.simulate(user, quote, Map.of())).get(1);
        assertEquals(new BigDecimal("112345.00"), n(original, "without_credit_ending_balance")
            .subtract(n(withDebt, "without_credit_ending_balance")));
    }

    @Test
    void volatilityIsNotCappedAndSeasonalityRequiresTwoYears() {
        var overview = new HashMap<>(finance.overview(user));
        overview.put("months", List.of(
            Map.of("month", "2026-07", "income", 1000000, "expenses", 20000),
            Map.of("month", "2026-08", "income", 100000, "expenses", 20000),
            Map.of("month", "2026-09", "income", 1000000, "expenses", 20000)));
        when(finance.overview(user)).thenReturn(overview);
        var volatileResult = engine.simulate(user, quote, Map.of());
        assertTrue(((Number) ((Map<?, ?>) volatileResult.get("basis")).get("revenue_variation")).doubleValue() > .5);
        assertEquals("LINEAR_TREND_RESIDUALS", volatileResult.get("history_method"));
        var history = new java.util.ArrayList<Map<String, Object>>();
        for (int i = 24; i >= 1; i--) {
            var month = java.time.YearMonth.from(today).minusMonths(i);
            history.add(Map.of("month", month.toString(), "income", month.getMonthValue() == 12 ? 2000000 : 1000000,
                "expenses", 700000));
        }
        overview.put("months", history);
        assertEquals("LINEAR_TREND_SEASONAL_RESIDUALS", engine.simulate(user, quote, Map.of()).get("history_method"));
    }

    @Test
    void unchangedSalesChannelDoesNotInventAnEffectWhenHistoricalDemandIsStressed() {
        var overview = new HashMap<>(finance.overview(user));
        overview.put("months", List.of(
            Map.of("month", "2026-07", "income", 1000000, "expenses", 700000),
            Map.of("month", "2026-08", "income", 500000, "expenses", 700000),
            Map.of("month", "2026-09", "income", 1000000, "expenses", 700000)));
        when(finance.overview(user)).thenReturn(overview);
        var plan = BusinessPlanTest.modernPlan();
        var c = BusinessPlanTest.rows(plan, "components").getFirst();
        BusinessPlanTest.params(c, "before").forEach((
            key,
            v) -> BusinessPlanTest.params(c, "after").put(key, BusinessPlanTest.range(((Number) v).doubleValue())));
        for (var outcome : outcomes(simulatePlan(plan))) {
            assertEquals(BigDecimal.ZERO.setScale(2), outcome.get("project_monthly_revenue"));
            assertEquals(BigDecimal.ZERO.setScale(2), outcome.get("project_monthly_expenses"));
        }
    }

    @Test
    void loanDisbursementsAndRecordedRepaymentsAreNotOperatingRevenueOrExpenses() {
        var original = engine.simulate(user, quote, Map.of());
        var overview = new HashMap<>(finance.overview(user));
        overview.put("months", List.of(
            Map.of("month", "2026-07", "income", 1500000, "expenses", 800000),
            Map.of("month", "2026-08", "income", 1000000, "expenses", 700000),
            Map.of("month", "2026-09", "income", 1000000, "expenses", 700000)));
        overview.put("transactions", List.of(
            Map.of("date", LocalDate.of(2026, 7, 1), "type", "INCOME", "category", "Выдача кредита", "amount", 500000),
            Map.of("date", LocalDate.of(2026, 7, 2), "type", "EXPENSE", "category", "Платёж по кредиту", "amount",
                100000)));
        when(finance.overview(user)).thenReturn(overview);
        var normalized = engine.simulate(user, quote, Map.of());
        assertEquals(n(outcomes(original).get(1), "ending_balance"), n(outcomes(normalized).get(1), "ending_balance"));
    }

    @Test
    void preparationAndWorkingCapitalAlsoRequireFunding() {
        var plan = cashPlan(1000000, 1000000);
        plan.put("launch_months", BusinessPlanTest.range(0));
        BusinessPlanTest.params(plan, "cashflow").put("expense_advance_days", BusinessPlanTest.range(90));
        var workingCapital = outcomes(simulatePlan(plan)).get(1);
        assertEquals(false, workingCapital.get("funding_feasible"));
        plan = cashPlan(1000000, 0);
        BusinessPlanTest.params(plan, "cashflow").put("prelaunch_monthly_expenses", BusinessPlanTest.range(2000000));
        assertEquals(false, outcomes(simulatePlan(plan)).get(1).get("funding_feasible"));
    }
}
