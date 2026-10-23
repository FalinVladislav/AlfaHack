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
    public static final Map<String, Double> TIMING = Map.of(
        "collection_delay_days", 365.0, "expense_advance_days", 365.0,
        "ramp_months", 24.0, "prelaunch_monthly_expenses", 1_000_000_000.0,
        "revenue_tax_rate", 1.0, "variable_cost_share", 1.0);
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
            if (!(value instanceof Number))
                throw new NumberFormatException();
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
        if (Boolean.TRUE.equals(plan.get("_revision_pending")))
            absent.add("revision_confirmation");
        boolean modern = Integer.valueOf(2).equals(plan.get("schema_version"));
        if (plan.containsKey("schema_version") && !modern)
            throw invalid("Поддерживается schema_version=2");
        if (modern || plan.containsKey("cashflow")) {
            if (!missing(plan, "cashflow", "", absent)) {
                var timing = object(plan.get("cashflow"));
                TIMING.forEach((
                    key,
                    max) -> {
                    if (!missing(timing, key, "cashflow.", absent))
                        range(timing.get(key), max, key.endsWith("days") || key.equals("ramp_months"));
                });
            }
        }
        if (plan.get("investment_schedule") != null) {
            if (!(plan.get("investment_schedule") instanceof List<?> stages) || stages.isEmpty() || stages.size() > 24)
                throw invalid("График инвестиций: от 1 до 24 этапов");
            double total = 0;
            var dates = new HashSet<Integer>();
            for (var stage : stages) {
                var s = object(stage);
                double month = number(s.get("month"), 24);
                if (month % 1 != 0 || !dates.add((int) month))
                    throw invalid("Месяцы инвестиций должны быть целыми и уникальными");
                total += number(s.get("share"), 1);
            }
            if (Math.abs(total - 1) > 0.000001)
                throw invalid("Доли этапов инвестиций должны давать 1");
        } else
            if (modern)
                absent.add("investment_schedule");
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
            if (!(plan.get("components") instanceof List<?> list) || list.size() > 8)
                throw invalid("Укажите от 1 до 8 независимых компонентов");
            if (list.isEmpty())
                absent.add("components");
            var ids = new HashSet<String>();
            var labels = new HashSet<String>();
            var signatures = new HashSet<Map<String, Object>>();
            var resources = new HashSet<String>();
            for (int i = 0; i < list.size(); i++) {
                var c = object(list.get(i));
                String path = "components[" + i + "].";
                for (String k : List.of("id", "label", "kind"))
                    if (!missing(c, k, path, absent))
                        text(c.get(k), k.equals("label") ? 300 : 60);
                if (c.get("id") != null && !ids.add(c.get("id").toString()))
                    throw invalid("Идентификаторы компонентов должны быть уникальными");
                if (c.get("label") != null
                    && !labels.add(c.get("label").toString().strip().toLowerCase(java.util.Locale.ROOT)))
                    throw invalid("Изменения одного канала объедините в один компонент");
                var signature = new LinkedHashMap<>(c);
                signature.remove("id");
                signature.remove("label");
                if (!signatures.add(signature))
                    throw invalid("Одинаковая экономика компонентов учитывается дважды");
                if (c.get("resource_ids") instanceof List<?> listResources) {
                    if (listResources.size() > 20)
                        throw invalid("Не более 20 общих ресурсов компонента");
                    for (var resource : listResources)
                        if (!resources.add(text(resource, 100).strip().toLowerCase(java.util.Locale.ROOT)))
                            throw invalid("Компоненты используют общий ресурс. Объедините связанные изменения");
                } else
                    if (modern)
                        absent.add(path + "resource_ids");
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
        if (modern) {
            var paths = new ArrayList<String>();
            numericPaths(plan, "", paths);
            var covered = new HashSet<String>();
            if (plan.get("sources") instanceof List<?> sources && sources.size() <= 500) {
                for (var source : sources) {
                    var s = object(source);
                    String path = text(s.get("path"), 200);
                    String kind = text(s.get("kind"), 20);
                    text(s.get("evidence"), 4000);
                    if (!List.of("USER", "ASSUMPTION").contains(kind)
                        || !(paths.contains(path) || (paths.contains(path + ".low") && paths.contains(path + ".base")
                            && paths.contains(path + ".high"))))
                        throw invalid("Некорректный путь или тип источника параметра: " + path);
                    covered.add(path);
                }
            }
            for (String path : paths)
                if (covered.stream().noneMatch(p -> path.equals(p) || path.startsWith(p + ".")))
                    absent.add("sources:" + path);
            if (!absent.isEmpty())
                return Map.of("status", "NEEDS_INPUTS", "missing_inputs", absent, "scenarios", List.of());
        }
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
                    // With a negative contribution margin, more sales cause a larger loss.
                    String volumeSide = r(after.get("price"), benefit) < r(after.get("unit_cost"), cost)
                        ? cost
                        : benefit;
                    double newUnits = SALES.subList(0, 4).stream().mapToDouble(k -> r(after.get(k), volumeSide)).min()
                        .orElseThrow();
                    String bottleneck = SALES.subList(0, 4).stream()
                        .min(java.util.Comparator.comparingDouble(k -> r(after.get(k), volumeSide))).orElseThrow();
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
                    metric.put("before_expenses", money(oldUnits * number(before.get("unit_cost"), 1_000_000_000)
                        + number(before.get("fixed_cost"), 1_000_000_000)));
                    metric.put("after_expenses", money(newUnits * r(after.get("unit_cost"), cost)
                        + r(after.get("fixed_cost"), cost)));
                    metric.put("bottleneck", bottleneck);
                    metric.put("before_state", before);
                    var chosen = new LinkedHashMap<String, Object>();
                    for (String key : SALES)
                        chosen.put(key, r(after.get(key), SALES.subList(0, 4).contains(key)
                            ? volumeSide
                            : key.equals("price") ? benefit : cost));
                    metric.put("after_state", chosen);
                } else {
                    deltaRevenue = r(c.get("revenue"), benefit) - r(c.get("lost_revenue"), cost);
                    deltaExpenses = r(c.get("expenses"), cost) - r(c.get("savings"), benefit);
                    metric.put("savings", money(r(c.get("savings"), benefit)));
                    metric.put("lost_revenue", money(r(c.get("lost_revenue"), cost)));
                }
                revenue += deltaRevenue;
                expenses += deltaExpenses;
                metric.put("monthly_revenue_delta", money(deltaRevenue));
                metric.put("monthly_expenses_delta", money(deltaExpenses));
                metric.put("monthly_net_delta", money(deltaRevenue - deltaExpenses));
                metrics.add(metric);
            }
            var outcome = new LinkedHashMap<String, Object>(
                Map.of("id", IDS.get(i), "project_cost", money(r(plan.get("investment"), cost)),
                    "delay_months", (int) r(plan.get("launch_months"), cost), "monthly_revenue", money(revenue),
                    "monthly_expenses", money(expenses), "monthly_net_delta", money(revenue - expenses), "metrics",
                    metrics));
            outcome.put("launch_range", plan.get("launch_months"));
            outcome.put("cashflow", plan.getOrDefault("cashflow", Map.of()));
            outcome.put("investment_schedule",
                plan.getOrDefault("investment_schedule", List.of(Map.of("month", 0, "share", 1))));
            outcomes.add(outcome);
        }
        return Map.of("status", "READY", "missing_inputs", List.of(), "scenarios", outcomes,
            "schema_version", modern ? 2 : 1,
            "warnings",
            modern
                ? List.of()
                : List.of(
                    "Старый план: сроки оплат, подготовительные расходы, налоги и раскрутка не заданы. Уточните план с помощником."),
            "method",
            "Продажи = min(спрос, мощности, персонал, сырьё). Денежный эффект — изменение выручки и расходов относительно текущей деятельности. Независимые компоненты суммируются один раз.");
    }

    private static void numericPaths(
        Object value,
        String path,
        List<String> out
    ) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                String key = entry.getKey().toString();
                if (!List.of("schema_version", "sources").contains(key))
                    numericPaths(entry.getValue(), path.isEmpty() ? key : path + "." + key, out);
            }
        } else
            if (value instanceof List<?> list) {
                for (int i = 0; i < list.size(); i++)
                    numericPaths(list.get(i), path + "[" + i + "]", out);
            } else
                if (value instanceof Number)
                    out.add(path);
    }

    public void verifySources(
        Map<String, Object> plan,
        List<Map<String, Object>> messages
    ) {
        if (!Integer.valueOf(2).equals(plan.get("schema_version")))
            return;
        if (!(plan.get("sources") instanceof List<?> sources))
            return;
        for (var source : sources) {
            var s = object(source);
            String evidence = text(s.get("evidence"), 4000);
            boolean found = "USER".equals(s.get("kind"))
                ? messages.stream().anyMatch(m -> "user".equals(m.get("role")) && m.containsKey("request_id")
                    && m.get("content") instanceof String content && content.contains(evidence))
                : plan.get("assumptions") instanceof List<?> assumptions && assumptions.contains(evidence);
            if (!found)
                throw invalid(
                    "Источник параметра не найден в сообщениях пользователя или явных допущениях: " + s.get("path"));
            if ("USER".equals(s.get("kind"))) {
                Object parameter = plan;
                String sourcePath = text(s.get("path"), 200);
                for (String segment : sourcePath.replace("[", ".").replace("]", "").split("\\.")) {
                    if (parameter instanceof Map<?, ?> map)
                        parameter = map.get(segment);
                    else
                        if (parameter instanceof List<?> list && segment.matches("\\d+")
                            && Integer.parseInt(segment) < list.size())
                            parameter = list.get(Integer.parseInt(segment));
                        else
                            throw invalid("Некорректный путь источника: " + sourcePath);
                }
                var numbers = evidenceNumbers(evidence);
                if (parameter instanceof Number n
                    && numbers.stream().noneMatch(v -> Math.abs(v - n.doubleValue()) < .000001))
                    throw invalid("Цитата не содержит значение параметра: " + sourcePath
                        + ". Производные оценки пометьте ASSUMPTION");
                if (parameter instanceof Map<?, ?> range)
                    for (String side : List.of("low", "base", "high")) {
                        double n = number(range.get(side), 1_000_000_000);
                        if (numbers.stream().noneMatch(v -> Math.abs(v - n) < .000001))
                            throw invalid("Цитата не содержит границы диапазона: " + sourcePath
                                + ". Производные оценки пометьте ASSUMPTION");
                    }
            }
        }
    }

    private static List<Double> evidenceNumbers(String text) {
        var numbers = new ArrayList<Double>();
        var matcher = java.util.regex.Pattern.compile(
            "(?iu)(?<![\\p{L}\\p{N}])(\\d+(?:[ \\u00a0\\u202f]\\d{3})*(?:[.,]\\d+)?)\\s*(?:(млрд|млн|тыс(?:яч(?:а|и)?)?|[kк])(?!\\p{L}))?")
            .matcher(text);
        while (matcher.find()) {
            double value = Double.parseDouble(matcher.group(1).replaceAll("[ \\u00a0\\u202f]", "").replace(',', '.'));
            String suffix = matcher.group(2);
            if (suffix != null)
                value *= suffix.equalsIgnoreCase("млрд") ? 1e9 : suffix.equalsIgnoreCase("млн") ? 1e6 : 1e3;
            numbers.add(value);
            if (text.substring(matcher.end()).stripLeading().startsWith("%"))
                numbers.add(value / 100);
        }
        if (text.toLowerCase(java.util.Locale.ROOT).matches("(?s).*(\\bнет\\b|нул\\p{L}*|\\bбез\\b).*"))
            numbers.add(0.0);
        return numbers;
    }

}
