package org.alfahack.features.scenarios.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.alfahack.core.errors.DomainException;

/**
 * A finite vocabulary of economic dependencies, never executable
 * model-generated formulas.
 */
public class BusinessPlan {

    public static final List<String> SALES = List.of("demand", "capacity", "staff_capacity", "supply_capacity", "price",
        "unit_cost", "fixed_cost");
    public static final List<String> CASH = List.of("revenue", "expenses", "savings", "lost_revenue");
    private static final List<String> IDS = List.of("STRESS", "BASE", "OPTIMISTIC");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>))
            throw invalid("Ожидается объект параметров");
        return (Map<String, Object>) value;
    }

    private static DomainException invalid(String message) {
        return DomainException.bad("INVALID_BUSINESS_PLAN", message);
    }

    private static String text(
        Object value,
        int max
    ) {
        if (!(value instanceof String s) || s.isBlank() || s.length() > max)
            throw invalid("Некорректное описание плана");
        return s;
    }

    private static double number(
        Object value,
        double max
    ) {
        try {
            double n = Double.parseDouble(String.valueOf(value));
            if (!Double.isFinite(n) || n < 0 || n > max)
                throw new NumberFormatException();
            return n;
        } catch (RuntimeException e) {
            throw invalid("Параметры должны быть неотрицательными числами в допустимом диапазоне");
        }
    }

    private static boolean missing(
        Map<String, Object> map,
        String key,
        String path,
        List<String> missing
    ) {
        if (map.get(key) != null)
            return false;
        missing.add(path + key);
        return true;
    }

    private static void range(
        Object value,
        double max,
        boolean integer
    ) {
        var r = object(value);
        double low = number(r.get("low"), max), base = number(r.get("base"), max), high = number(r.get("high"), max);
        if (low > base || base > high || (integer && (low % 1 != 0 || base % 1 != 0 || high % 1 != 0)))
            throw invalid("Диапазон должен удовлетворять low ≤ base ≤ high; задержка задаётся целыми месяцами");
    }

    private static double r(
        Object value,
        String side
    ) {
        return number(object(value).get(side), 1_000_000_000);
    }

    private static BigDecimal money(double n) {
        if (!Double.isFinite(n) || Math.abs(n) > 1_000_000_000)
            throw invalid("Месячный эффект выходит за поддерживаемый диапазон ±1 млрд ₽");
        return BigDecimal.valueOf(n).setScale(2, RoundingMode.HALF_UP);
    }

    public Map<String, Object> evaluate(Map<String, Object> plan) {
        var absent = new ArrayList<String>();
        if (!missing(plan, "title", "", absent))
            text(plan.get("title"), 300);
        for (String key : List.of("investment", "launch_months"))
            if (!missing(plan, key, "", absent))
                range(plan.get(key), key.equals("launch_months") ? 24 : 1_000_000_000, key.equals("launch_months"));
        if (!missing(plan, "assumptions", "", absent)) {
            if (!(plan.get("assumptions") instanceof List<?> assumptions) || assumptions.size() > 12)
                throw invalid("Не более 12 допущений");
            for (var a : assumptions)
                text(a, 1000);
        }
        List<Map<String, Object>> components = new ArrayList<>();
        if (!missing(plan, "components", "", absent)) {
            if (!(plan.get("components") instanceof List<?> list) || list.isEmpty() || list.size() > 8)
                throw invalid("Укажите от 1 до 8 независимых компонентов");
            var ids = new HashSet<String>();
            for (int i = 0; i < list.size(); i++) {
                var c = object(list.get(i));
                String path = "components[" + i + "].";
                for (String k : List.of("id", "label", "kind"))
                    if (!missing(c, k, path, absent))
                        text(c.get(k), k.equals("label") ? 300 : 60);
                if (c.get("id") != null && !ids.add(c.get("id").toString()))
                    throw invalid("Идентификаторы компонентов должны быть уникальными");
                if ("SALES".equals(c.get("kind"))) {
                    if (!missing(c, "unit", path, absent))
                        text(c.get("unit"), 60);
                    for (String state : List.of("before", "after")) {
                        if (missing(c, state, path, absent))
                            continue;
                        var params = object(c.get(state));
                        for (String k : SALES)
                            if (!missing(params, k, path + state + ".", absent)) {
                                if (state.equals("after"))
                                    range(params.get(k), 1_000_000_000, false);
                                else
                                    number(params.get(k), 1_000_000_000);
                            }
                    }
                } else
                    if ("CASHFLOW".equals(c.get("kind"))) {
                        for (String k : CASH)
                            if (!missing(c, k, path, absent))
                                range(c.get(k), 1_000_000_000, false);
                    } else
                        if (c.get("kind") != null)
                            throw invalid(
                                "Поддерживаются SALES и CASHFLOW. Для других зависимостей нужны явные денежные допущения");
                components.add(c);
            }
        }
        if (!absent.isEmpty())
            return Map.of("status", "NEEDS_INPUTS", "missing_inputs", absent, "scenarios", List.of());
        var outcomes = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 3; i++) {
            String benefit = i == 0 ? "low" : i == 2 ? "high" : "base";
            String cost = i == 0 ? "high" : i == 2 ? "low" : "base";
            double revenue = 0, expenses = 0;
            var metrics = new ArrayList<Map<String, Object>>();
            for (var c : components) {
                Map<String, Object> metric = new LinkedHashMap<>();
                metric.put("id", c.get("id"));
                metric.put("label", c.get("label"));
                metric.put("kind", c.get("kind"));
                double deltaRevenue, deltaExpenses;
                if ("SALES".equals(c.get("kind"))) {
                    var before = object(c.get("before"));
                    var after = object(c.get("after"));
                    double oldUnits = SALES.subList(0, 4).stream()
                        .mapToDouble(k -> number(before.get(k), 1_000_000_000)).min().orElseThrow();
                    double newUnits = SALES.subList(0, 4).stream().mapToDouble(k -> r(after.get(k), benefit)).min()
                        .orElseThrow();
                    String bottleneck = SALES.subList(0, 4).stream()
                        .min(java.util.Comparator.comparingDouble(k -> r(after.get(k), benefit))).orElseThrow();
                    double oldRevenue = oldUnits * number(before.get("price"), 1_000_000_000);
                    double newRevenue = newUnits * r(after.get("price"), benefit);
                    deltaRevenue = newRevenue - oldRevenue;
                    deltaExpenses = newUnits * r(after.get("unit_cost"), cost) + r(after.get("fixed_cost"), cost)
                        - oldUnits * number(before.get("unit_cost"), 1_000_000_000)
                        - number(before.get("fixed_cost"), 1_000_000_000);
                    metric.put("unit", c.get("unit"));
                    metric.put("before_units", money(oldUnits));
                    metric.put("after_units", money(newUnits));
                    metric.put("before_revenue", money(oldRevenue));
                    metric.put("after_revenue", money(newRevenue));
                    metric.put("bottleneck", bottleneck);
                } else {
                    deltaRevenue = r(c.get("revenue"), benefit) - r(c.get("lost_revenue"), cost);
                    deltaExpenses = r(c.get("expenses"), cost) - r(c.get("savings"), benefit);
                }
                revenue += deltaRevenue;
                expenses += deltaExpenses;
                metric.put("monthly_revenue_delta", money(deltaRevenue));
                metric.put("monthly_expenses_delta", money(deltaExpenses));
                metric.put("monthly_net_delta", money(deltaRevenue - deltaExpenses));
                metrics.add(metric);
            }
            outcomes.add(Map.of("id", IDS.get(i), "project_cost", money(r(plan.get("investment"), cost)),
                "delay_months", (int) r(plan.get("launch_months"), cost), "monthly_revenue", money(revenue),
                "monthly_expenses", money(expenses), "monthly_net_delta", money(revenue - expenses), "metrics",
                metrics));
        }
        return Map.of("status", "READY", "missing_inputs", List.of(), "scenarios", outcomes,
            "method",
            "Продажи = min(спрос, мощности, персонал, сырьё). Денежный эффект — изменение выручки и расходов относительно текущей деятельности. Независимые компоненты суммируются один раз.");
    }
}
