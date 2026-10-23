package org.alfahack;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.alfahack.core.errors.DomainException;
import org.alfahack.features.scenarios.application.BusinessPlan;
import org.junit.jupiter.api.Test;

class BusinessPlanTest {

    static Map<String, Object> range(double n) {
        return Map.of("low", n, "base", n, "high", n);
    }

    static Map<String, Object> sales() {
        var before = Map.<String, Object>of("demand", 100, "capacity", 80, "staff_capacity", 80, "supply_capacity", 80,
            "price", 100, "unit_cost", 50, "fixed_cost", 1000);
        var after = new HashMap<String, Object>();
        before.forEach((
            k,
            v) -> after.put(k, range(((Number) v).doubleValue())));
        after.put("capacity", range(120));
        after.put("staff_capacity", range(120));
        after.put("supply_capacity", range(120));
        return new HashMap<>(Map.of("id", "main", "label", "Основной канал", "kind", "SALES", "unit", "заказ",
            "before", new HashMap<>(before), "after", after));
    }

    static Map<String, Object> plan() {
        return new HashMap<>(Map.of("title", "Изменение бизнеса", "investment", range(1000000),
            "launch_months", range(2), "assumptions", List.of("Диапазоны требуют проверки предпринимателем"),
            "components", List.of(sales())));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(
        Map<String, Object> data,
        String key
    ) {
        return (List<Map<String, Object>>) data.get(key);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> params(
        Map<String, Object> c,
        String key
    ) {
        return (Map<String, Object>) c.get(key);
    }

    static Map<String, Object> metric(Map<String, Object> plan) {
        return rows(rows(new BusinessPlan().evaluate(plan), "scenarios").get(1), "metrics").getFirst();
    }

    @Test
    void equipmentCapacityDoesNotCreateUnlimitedDemand() {
        var m = metric(plan());
        assertEquals(new BigDecimal("100.00"), m.get("after_units"));
        assertEquals("demand", m.get("bottleneck"));
        assertEquals(new BigDecimal("1000.00"), m.get("monthly_net_delta"));
    }

    @Test
    void staffAndRawMaterialRemainIndependentBottlenecks() {
        var plan = plan();
        var c = rows(plan, "components").getFirst();
        params(c, "after").put("staff_capacity", range(80));
        assertEquals(new BigDecimal("0.00"), metric(plan).get("monthly_net_delta"));
        params(c, "after").put("supply_capacity", range(50));
        assertEquals(new BigDecimal("50.00"), metric(plan).get("after_units"));
    }

    @Test
    void hiringAccountsForPayrollAndIncrementalSalesRatherThanAllRevenue() {
        var plan = plan();
        var c = rows(plan, "components").getFirst();
        params(c, "before").put("staff_capacity", 20);
        params(c, "after").put("staff_capacity", range(40));
        params(c, "after").put("fixed_cost", range(3000));
        var m = metric(plan);
        assertEquals(new BigDecimal("2000.00"), m.get("monthly_revenue_delta"));
        assertEquals(new BigDecimal("3000.00"), m.get("monthly_expenses_delta"));
        assertEquals(new BigDecimal("-1000.00"), m.get("monthly_net_delta"));
    }

    @Test
    void newLocationHasZeroCurrentActivityAndOwnLaunchCosts() {
        var plan = plan();
        var c = rows(plan, "components").getFirst();
        for (var key : BusinessPlan.SALES)
            params(c, "before").put(key, 0);
        assertEquals(new BigDecimal("10000.00"), metric(plan).get("monthly_revenue_delta"));
        assertEquals(new BigDecimal("6000.00"), metric(plan).get("monthly_expenses_delta"));
    }

    @Test
    void savingsAndCashflowCanBeModelledWithoutSalesOrProfitGrowth() {
        var plan = plan();
        plan.put("components", List.of(Map.of("id", "saving", "label", "Автоматизация", "kind", "CASHFLOW",
            "revenue", range(0), "expenses", range(1000), "savings", range(1500), "lost_revenue", range(0))));
        assertEquals(new BigDecimal("500.00"), metric(plan).get("monthly_net_delta"));
    }

    @Test
    void uncertaintyUsesLowDemandButHighCostsAndDelayInStress() {
        var plan = plan();
        var c = rows(plan, "components").getFirst();
        params(c, "after").put("demand", Map.of("low", 60, "base", 100, "high", 140));
        params(c, "after").put("fixed_cost", Map.of("low", 500, "base", 1000, "high", 2000));
        plan.put("launch_months", Map.of("low", 1, "base", 2, "high", 4));
        var scenarios = rows(new BusinessPlan().evaluate(plan), "scenarios");
        assertEquals(4, scenarios.getFirst().get("delay_months"));
        assertEquals(1, scenarios.getLast().get("delay_months"));
        assertTrue(((BigDecimal) scenarios.getFirst().get("monthly_net_delta"))
            .compareTo((BigDecimal) scenarios.getLast().get("monthly_net_delta")) < 0);
    }

    @Test
    void missingParametersAreReportedInsteadOfInvented() {
        var plan = plan();
        params(rows(plan, "components").getFirst(), "after").remove("demand");
        var result = new BusinessPlan().evaluate(plan);
        assertEquals("NEEDS_INPUTS", result.get("status"));
        assertEquals(List.of("components[0].after.demand"), result.get("missing_inputs"));
        assertTrue(rows(result, "scenarios").isEmpty());
    }

    @Test
    void invalidRangesUnknownFormulasAndDuplicateComponentsAreRejected() {
        var plan = plan();
        plan.put("investment", Map.of("low", 3, "base", 2, "high", 1));
        assertThrows(DomainException.class, () -> new BusinessPlan().evaluate(plan));
        plan.put("investment", range(100));
        plan.put("components", List.of(sales(), sales()));
        assertThrows(DomainException.class, () -> new BusinessPlan().evaluate(plan));
        var c = sales();
        c.put("kind", "RUN_PYTHON");
        plan.put("components", List.of(c));
        assertThrows(DomainException.class, () -> new BusinessPlan().evaluate(plan));
    }
}
