package org.alfahack.features.forecast.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.alfahack.core.domain.User;
import org.alfahack.core.domain.Util;
import org.alfahack.core.errors.DomainException;
import org.alfahack.features.finance.port.FinanceUseCases;
import org.alfahack.features.forecast.port.CreditScenarioUseCases;
import org.alfahack.features.forecast.port.ForecastRepository;
import org.alfahack.features.products.port.ProductCatalog;

/**
 * Conditional cash-flow simulation; scenario labels do not imply probabilities.
 */
public class CreditScenarios implements CreditScenarioUseCases {

    private final FinanceUseCases finance;
    private final ForecastRepository repository;
    private final ProductCatalog products;
    private final LocalDate today;

    public CreditScenarios(
        FinanceUseCases finance,
        ForecastRepository repository,
        ProductCatalog products,
        String today
    ) {
        this.finance = finance;
        this.repository = repository;
        this.products = products;
        this.today = LocalDate.parse(today);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(
        Map<String, Object> data,
        String key
    ) {
        return (List<Map<String, Object>>) data.get(key);
    }

    private static double value(Object n) {
        return n == null ? 0 : Double.parseDouble(n.toString());
    }

    private static BigDecimal money(double n) {
        return BigDecimal.valueOf(n).setScale(2, RoundingMode.HALF_UP);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static double setting(
        Map<String, Object> effect,
        String key,
        String side,
        double fallback
    ) {
        var range = object(object(effect.get("cashflow")).get(key));
        return range.isEmpty() ? fallback : value(range.get(side));
    }

    private static void reconcile(
        Map<String, Object> effects,
        double income,
        double expenses
    ) {
        for (var base : rows(effects, "scenarios")) {
            double currentRevenue = 0, currentExpenses = 0, savings = 0, lostRevenue = 0;
            for (var metric : rows(base, "metrics")) {
                currentRevenue += value(metric.get("before_revenue"));
                currentExpenses += value(metric.get("before_expenses"));
                savings += value(metric.get("savings"));
                lostRevenue += value(metric.get("lost_revenue"));
            }
            if (currentRevenue > income + .01 || currentExpenses > expenses + .01)
                throw DomainException.bad("BUSINESS_HISTORY_MISMATCH",
                    "Текущие каналы плана превышают историческую выручку или расходы. Сверьте before с историей бизнеса");
            if (savings > expenses - currentExpenses + .01 || lostRevenue > income - currentRevenue + .01)
                throw DomainException.bad("BUSINESS_HISTORY_MISMATCH",
                    "Экономия или потеря выручки превышает исторический поток вне уже учтённых каналов");
        }
    }

    private static double[] channel(
        Map<String, Object> state,
        double demandFactor
    ) {
        double units = Math.min(value(state.get("demand")) * demandFactor,
            Math.min(value(state.get("capacity")),
                Math.min(value(state.get("staff_capacity")), value(state.get("supply_capacity")))));
        return new double[]{units * value(state.get("price")),
                units * value(state.get("unit_cost")) + value(state.get("fixed_cost")),
                units * value(state.get("unit_cost")), units};
    }

    private static double input(
        Map<String, Object> body,
        String key,
        double fallback
    ) {
        if (!body.containsKey(key))
            return fallback;
        try {
            double n = value(body.get(key));
            if (body.get(key) == null || !Double.isFinite(n) || n < 0 || n > 1_000_000_000)
                throw new NumberFormatException();
            return n;
        } catch (RuntimeException e) {
            throw DomainException.bad("INVALID_SCENARIO_INPUT", "Укажите неотрицательную сумму до 1 млрд ₽: " + key);
        }
    }

    @Override
    public Map<String, Object> simulate(
        User user,
        Map<String, Object> quote,
        Map<String, Object> body
    ) {
        return calculate(user, quote, body, null);
    }

    @Override
    public Map<String, Object> simulatePlan(
        User user,
        Map<String, Object> quote,
        Map<String, Object> effects
    ) {
        return calculate(user, quote, Map.of(), effects);
    }

    private Map<String, Object> calculate(
        User user,
        Map<String, Object> quote,
        Map<String, Object> body,
        Map<String, Object> effects
    ) {
        user.client();
        var draft = repository.draft((String) quote.get("draft_id"));
        if (((Number) draft.get("version")).intValue() != ((Number) quote.get("draft_version")).intValue())
            throw DomainException.conflict("QUOTE_STALE", "Условия изменились. Обновите расчёт кредита");
        double amount = value(quote.get("amount"));
        boolean gap = "CASH_GAP".equals(draft.get("purpose"));
        double budget = input(body, "project_cost", gap ? 0 : amount);
        double revenue = input(body, "monthly_revenue", 0);
        double expenses = input(body, "monthly_expenses", 0);
        if (gap && (budget != 0 || revenue != 0 || expenses != 0))
            throw DomainException.bad("INVALID_SCENARIO_INPUT",
                "Кассовый разрыв моделируется без отдельного проекта роста");
        int delay = body.containsKey("delay_months")
            ? Util.number(body.get("delay_months"), 0, 24, "delay_months")
            : "EXPANSION".equals(draft.get("purpose")) ? 2 : 0;
        boolean specified = body.containsKey("monthly_revenue") && body.containsKey("monthly_expenses");
        if (body.containsKey("monthly_revenue") != body.containsKey("monthly_expenses"))
            throw DomainException.bad("INVALID_SCENARIO_INPUT", "Укажите дополнительную выручку и расходы вместе");
        var overview = finance.overview(user);
        var financing = new java.util.HashMap<YearMonth, double[]>();
        if (overview.get("transactions") instanceof List<?> transactions)
            for (var item : transactions) {
                var transaction = object(item);
                boolean disbursement = "INCOME".equals(transaction.get("type"))
                    && "Выдача кредита".equals(transaction.get("category"));
                boolean repayment = "EXPENSE".equals(transaction.get("type"))
                    && "Платёж по кредиту".equals(transaction.get("category"));
                if (disbursement || repayment) {
                    var totals = financing.computeIfAbsent(YearMonth.from((LocalDate) transaction.get("date")),
                        k -> new double[2]);
                    totals[disbursement ? 0 : 1] += value(transaction.get("amount"));
                }
            }
        var months = rows(overview, "months").stream()
            .filter(m -> YearMonth.parse(m.get("month").toString()).isBefore(YearMonth.from(today)))
            .sorted(java.util.Comparator.comparing(m -> m.get("month").toString())).map(m -> {
                Map<String, Object> normalized = new LinkedHashMap<>(m);
                var totals = financing.getOrDefault(YearMonth.parse(m.get("month").toString()), new double[2]);
                if (m.get("income") != null)
                    normalized.put("income", value(m.get("income")) - totals[0]);
                if (m.get("expenses") != null)
                    normalized.put("expenses", value(m.get("expenses")) - totals[1]);
                if (normalized.get("income") != null && normalized.get("expenses") != null)
                    normalized.put("net", value(normalized.get("income")) - value(normalized.get("expenses")));
                return normalized;
            }).toList();
        months = months.subList(Math.max(0, months.size() - 36), months.size());
        boolean continuous = !months.isEmpty();
        for (int i = 1; i < months.size(); i++)
            continuous &= YearMonth.parse(months.get(i - 1).get("month").toString()).plusMonths(1)
                .equals(YearMonth.parse(months.get(i).get("month").toString()));
        boolean fresh = !months.isEmpty() && YearMonth.parse(months.getLast().get("month").toString())
            .equals(YearMonth.from(today).minusMonths(1));
        if (months.size() < 3 || months.stream().anyMatch(m -> m.get("income") == null || m.get("expenses") == null)
            || !continuous || !fresh
            || months.stream().anyMatch(m -> !Double.isFinite(value(m.get("income"))) || value(m.get("income")) < 0
                || !Double.isFinite(value(m.get("expenses"))) || value(m.get("expenses")) < 0)
            || months.stream().mapToDouble(m -> value(m.get("income"))).average().orElse(0) <= 0)
            return Map.of("status", "INSUFFICIENT_DATA", "quote_id", quote.get("id"), "scenarios", List.of(),
                "message",
                "Нужны минимум три последовательных полных месяца до прошлого месяца включительно, без пропусков. Сценарии пока не рассчитаны.");
        double income = months.stream().mapToDouble(m -> value(m.get("income"))).average().orElseThrow();
        double historicalExpenses = months.stream().mapToDouble(m -> value(m.get("expenses"))).average().orElseThrow();
        var loans = rows(overview, "loans");
        // Synthetic history includes pre-existing debt inside expenses. Issued loans
        // are separate.
        double embeddedDebt = "SYNTHETIC".equals(overview.get("data_origin"))
            ? loans.stream().filter(l -> "EXISTING".equals(l.get("source")))
                .mapToDouble(l -> value(l.get("monthly_payment"))).sum()
            : 0;
        double operatingExpenses = Math.max(0, historicalExpenses - embeddedDebt);
        var history = new HistoricalCashflow(months, embeddedDebt);
        double spread = history.spread();
        if (effects != null)
            reconcile(effects, income, operatingExpenses);
        LocalDate issue = draft.get("needed_by") == null ? today.plusDays(1) : (LocalDate) draft.get("needed_by");
        if (!issue.isAfter(today))
            throw DomainException.conflict("QUOTE_STALE", "Дата выдачи уже наступила. Обновите условия кредита");
        // Cover repayment as well as the slowest launch, collection and ramp-up.
        int horizon = quote.get("term_months") == null
            ? 12
            : Math.max(12, ((Number) quote.get("term_months")).intValue());
        if (effects == null && specified)
            horizon = Math.max(horizon, delay + 14);
        if (effects != null)
            for (var effect : rows(effects, "scenarios")) {
                int finalStage = rows(effect, "investment_schedule").stream().filter(s -> value(s.get("share")) > 0)
                    .mapToInt(s -> ((Number) s.get("month")).intValue()).max().orElse(0);
                horizon = Math.max(horizon,
                    Math.max(finalStage, ((Number) object(effect.get("launch_range")).get("high")).intValue())
                        + (int) setting(effect, "ramp_months", "high", 0) + 12
                        + (int) Math.ceil(setting(effect, "collection_delay_days", "high", 0) / 28));
            }
        LocalDate end = issue.plusMonths(horizon);
        var events = repository.plannedEvents(user.company(), today, end);
        double openingBalance = value(repository.balance(user.company()).get("balance"));
        var debtFlows = new java.util.HashMap<LocalDate, Double>();
        for (var loan : loans) {
            if (loan.get("schedule") instanceof List<?> exact && !exact.isEmpty()) {
                for (var item : exact) {
                    var payment = object(item);
                    LocalDate due = (LocalDate) payment.get("due_date");
                    debtFlows.merge(due.isAfter(today) ? due : today.plusDays(1), value(payment.get("payment")),
                        Double::sum);
                }
                continue;
            }
            LocalDate next = (LocalDate) loan.get("next_payment_date");
            LocalDate last = (LocalDate) loan.get("end_date");
            if (next == null)
                continue;
            double debt = loan.get("remaining_debt") == null
                ? Double.POSITIVE_INFINITY
                : value(loan.get("remaining_debt"));
            for (int period = 0; period < 1200 && debt > .005; period++) {
                LocalDate due = next.plusMonths(period);
                if (due.isAfter(end) || last != null && due.isAfter(last))
                    break;
                double interest = Double.isFinite(debt) ? debt * value(loan.get("rate")) / 12 : 0;
                double payment = Math.min(value(loan.get("monthly_payment")), debt + interest);
                debtFlows.merge(due.isAfter(today) ? due : today.plusDays(1), payment, Double::sum);
                debt -= Math.max(0, payment - interest);
                if (loan.get("duration_days") != null)
                    break;
            }
        }
        var schedule = rows(products.calculate(products.get((String) quote.get("product_id")),
            new BigDecimal(quote.get("amount").toString()),
            quote.get("term_months") == null ? null : ((Number) quote.get("term_months")).intValue(),
            quote.get("duration_days") == null ? null : ((Number) quote.get("duration_days")).intValue()), "schedule");
        LocalDate maturity = quote.get("duration_days") == null
            ? issue.plusMonths(((Number) quote.get("term_months")).longValue())
            : issue.plusDays(((Number) quote.get("duration_days")).longValue());
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (int variant = -1; variant <= 1; variant++) {
            double factor = Math.max(0, 1 + variant * spread);
            double projectFactor = variant < 0 ? .5 : variant > 0 ? 1.2 : 1;
            double projectExpenseFactor = variant < 0 ? 1.2 : 1;
            Map<String, Object> effect = effects == null ? null : rows(effects, "scenarios").get(variant + 1);
            double scenarioRevenue = effect == null ? revenue * projectFactor : value(effect.get("monthly_revenue"));
            double scenarioExpenses = effect == null
                ? expenses * projectExpenseFactor
                : value(effect.get("monthly_expenses"));
            double projectVariableExpenses = 0;
            double beforeRevenue = 0, beforeExpenses = 0, stressedBeforeRevenue = 0, stressedBeforeExpenses = 0;
            var runtimeMetrics = new ArrayList<Map<String, Object>>();
            if (effect != null)
                for (var metric : rows(effect, "metrics")) {
                    var runtimeMetric = new LinkedHashMap<>(metric);
                    runtimeMetrics.add(runtimeMetric);
                    if (!"SALES".equals(metric.get("kind")))
                        continue;
                    var futureState = object(metric.get("after_state"));
                    double channelFactor = value(futureState.get("price")) < value(futureState.get("unit_cost"))
                        ? Math.max(0, 1 - variant * spread)
                        : factor;
                    var previous = channel(object(metric.get("before_state")), channelFactor);
                    var future = channel(futureState, channelFactor);
                    scenarioRevenue += future[0] - previous[0] - value(metric.get("monthly_revenue_delta"));
                    scenarioExpenses += future[1] - previous[1] - value(metric.get("monthly_expenses_delta"));
                    projectVariableExpenses += future[2] - previous[2];
                    beforeRevenue += value(metric.get("before_revenue"));
                    beforeExpenses += value(metric.get("before_expenses"));
                    stressedBeforeRevenue += previous[0];
                    stressedBeforeExpenses += previous[1];
                    runtimeMetric.put("before_units", money(previous[3]));
                    runtimeMetric.put("after_units", money(future[3]));
                    runtimeMetric.put("before_revenue", money(previous[0]));
                    runtimeMetric.put("after_revenue", money(future[0]));
                    runtimeMetric.put("before_expenses", money(previous[1]));
                    runtimeMetric.put("after_expenses", money(future[1]));
                    runtimeMetric.put("monthly_revenue_delta", money(future[0] - previous[0]));
                    runtimeMetric.put("monthly_expenses_delta", money(future[1] - previous[1]));
                    runtimeMetric.put("monthly_net_delta", money(future[0] - previous[0] - future[1] + previous[1]));
                }
            if (effect != null) {
                effect = new LinkedHashMap<>(effect);
                effect.put("metrics", runtimeMetrics);
                effect.put("monthly_revenue", money(scenarioRevenue));
                effect.put("monthly_expenses", money(scenarioExpenses));
                effect.put("monthly_net_delta", money(scenarioRevenue - scenarioExpenses));
            }
            double scenarioBudget = effect == null ? budget : value(effect.get("project_cost"));
            int lowDelay = effect == null
                ? delay + (variant < 0 ? 2 : 0)
                : ((Number) object(effect.get("launch_range")).get("low")).intValue();
            int highDelay = effect == null
                ? lowDelay
                : ((Number) object(effect.get("launch_range")).get("high")).intValue();
            if (variant == 0 && effect != null)
                lowDelay = highDelay = ((Number) object(effect.get("launch_range")).get("base")).intValue();
            Map<String, Object> selected = null;
            for (int candidateDelay = lowDelay; candidateDelay <= highDelay; candidateDelay++) {
                LocalDate benefit = issue.plusMonths(candidateDelay);
                String costSide = variant < 0 ? "high" : variant > 0 ? "low" : "base";
                String benefitSide = variant < 0 ? "low" : variant > 0 ? "high" : "base";
                int collectionDelay = effect == null
                    ? 0
                    : (int) setting(effect, "collection_delay_days", scenarioRevenue < 0 ? benefitSide : costSide, 0);
                int expenseAdvance = effect == null
                    ? 0
                    : (int) setting(effect, "expense_advance_days", scenarioExpenses < 0 ? benefitSide : costSide, 0);
                int ramp = effect == null
                    ? 0
                    : (int) setting(effect, "ramp_months", scenarioRevenue < 0 ? benefitSide : costSide, 0);
                double prep = effect == null ? 0 : setting(effect, "prelaunch_monthly_expenses", costSide, 0);
                double tax = effect == null ? 0 : setting(effect, "revenue_tax_rate", costSide, 0);
                double variableShare = effect == null
                    ? .5
                    : setting(effect, "variable_cost_share", factor < 1 ? benefitSide : costSide, .5);
                double balance = openingBalance;
                double without = balance, minimum = balance, baselineMinimum = balance, remaining = 0, paid = 0;
                LocalDate firstNegative = balance < 0 ? today : null;
                List<Map<String, Object>> points = new ArrayList<>();
                points.add(Map.of("date", today, "balance", money(balance), "without_credit", money(without)));
                var flows = new java.util.HashMap<LocalDate, Double>();
                var projectFlows = new java.util.HashMap<LocalDate, Double>();
                var projectCosts = new java.util.HashMap<LocalDate, Double>();
                var investments = new java.util.HashMap<LocalDate, Double>();
                if (effect == null)
                    investments.put(issue, scenarioBudget);
                else
                    for (var stage : rows(effect, "investment_schedule"))
                        investments.merge(issue.plusMonths(((Number) stage.get("month")).intValue()),
                            scenarioBudget * value(stage.get("share")), Double::sum);
                LocalDate fullyInvested = investments.entrySet().stream().filter(e -> e.getValue() > 0)
                    .map(Map.Entry::getKey).max(LocalDate::compareTo).orElse(issue);
                if (benefit.isBefore(fullyInvested))
                    benefit = fullyInvested;
                // Schedule accrual and payment separately. Costs before the simulation
                // boundary are charged at issue and explicitly disclosed below.
                double receivables = 0, prepaidBeforeIssue = 0;
                for (LocalDate activity = benefit; !activity.isAfter(end.plusDays(expenseAdvance)); activity = activity
                    .plusDays(1)) {
                    long elapsed = java.time.temporal.ChronoUnit.DAYS.between(benefit, activity) + 1;
                    long rampDays = ramp == 0
                        ? 1
                        : java.time.temporal.ChronoUnit.DAYS.between(benefit, benefit.plusMonths(ramp));
                    double load = Math.min(1, (double) elapsed / rampDays);
                    double dailyRevenue = scenarioRevenue * load / activity.lengthOfMonth();
                    double dailyExpenses = (scenarioExpenses - projectVariableExpenses + projectVariableExpenses * load)
                        / activity.lengthOfMonth();
                    LocalDate collected = activity.plusDays(collectionDelay);
                    if (!activity.isAfter(end)) {
                        if (collected.isAfter(end))
                            receivables += dailyRevenue;
                        else
                            projectFlows.merge(collected, dailyRevenue - Math.max(0, dailyRevenue) * tax, Double::sum);
                    }
                    LocalDate paidOn = activity.minusDays(expenseAdvance);
                    if (paidOn.isBefore(issue)) {
                        prepaidBeforeIssue += dailyExpenses;
                        paidOn = issue;
                    }
                    if (!paidOn.isAfter(end))
                        projectCosts.merge(paidOn, -dailyExpenses, Double::sum);
                }
                // Known calendar flows replace part of the monthly budget instead of being
                // added twice.
                for (LocalDate start = today.plusDays(1); !start.isAfter(end);) {
                    LocalDate through = YearMonth.from(start).atEndOfMonth();
                    if (through.isAfter(end))
                        through = end;
                    long count = java.time.temporal.ChronoUnit.DAYS.between(start, through) + 1;
                    double share = (double) count / start.lengthOfMonth();
                    double knownIncome = 0, knownExpense = 0;
                    for (var event : events) {
                        LocalDate date = (LocalDate) event.get("event_date");
                        if (date.isBefore(start) || date.isAfter(through))
                            continue;
                        double n = value(event.get("amount"));
                        flows.merge(date, n, Double::sum);
                        if (n > 0)
                            knownIncome += n;
                        else
                            knownExpense -= n;
                    }
                    YearMonth forecastMonth = YearMonth.from(start);
                    double forecastIncome = history.income(forecastMonth);
                    double forecastExpenses = history.expenses(forecastMonth);
                    double effectiveFactor = forecastIncome < forecastExpenses * variableShare
                        ? Math.max(0, 1 - variant * spread)
                        : factor;
                    double scenarioIncome = Math.max(0, forecastIncome - beforeRevenue) * effectiveFactor
                        + stressedBeforeRevenue;
                    double scenarioOperatingExpenses = Math.max(0, forecastExpenses - beforeExpenses)
                        * (1 - variableShare + variableShare * effectiveFactor) + stressedBeforeExpenses;
                    double residual = (Math.max(0, scenarioIncome * share - knownIncome)
                        - Math.max(0, scenarioOperatingExpenses * share - knownExpense)) / count;
                    for (LocalDate date = start; !date.isAfter(through); date = date.plusDays(1))
                        flows.merge(date, residual, Double::sum);
                    start = through.plusDays(1);
                }
                boolean fundingFeasible = true;
                LocalDate fundingFailure = null;
                double fundingShortfall = 0;
                for (LocalDate date = today.plusDays(1); !date.isAfter(end); date = date.plusDays(1)) {
                    double operating = flows.getOrDefault(date, 0.0);
                    operating -= debtFlows.getOrDefault(date, 0.0);
                    without += operating;
                    balance += operating;
                    if (date.equals(issue)) {
                        balance += amount;
                        remaining = amount;
                    }
                    double investment = investments.getOrDefault(date, 0.0);
                    if (investment > 0 && balance < investment) {
                        fundingFeasible = false;
                        fundingShortfall = Math.max(fundingShortfall, investment - balance);
                        if (fundingFailure == null)
                            fundingFailure = date;
                    }
                    balance -= investment;
                    double preparationPayment = !date.isBefore(issue) && date.isBefore(benefit)
                        ? prep / date.lengthOfMonth()
                        : 0;
                    balance -= preparationPayment;
                    // An unfunded project cannot generate the promised operating benefit.
                    if (fundingFeasible) {
                        balance += projectFlows.getOrDefault(date, 0.0);
                        balance += projectCosts.getOrDefault(date, 0.0);
                    }
                    if (!date.isBefore(issue) && balance < 0
                        && (preparationPayment > 0 || projectCosts.getOrDefault(date, 0.0) < 0)) {
                        fundingFeasible = false;
                        fundingShortfall = Math.max(fundingShortfall, -balance);
                        if (fundingFailure == null)
                            fundingFailure = date;
                    }
                    for (var payment : schedule) {
                        long period = ((Number) payment.get("period")).longValue();
                        LocalDate due = quote.get("duration_days") == null
                            ? issue.plusMonths(period)
                            : issue.plusDays(period);
                        if (date.equals(due)) {
                            double n = value(payment.get("payment"));
                            balance -= n;
                            paid += n;
                            remaining = value(payment.get("remaining"));
                        }
                    }
                    if (balance < 0 && firstNegative == null)
                        firstNegative = date;
                    minimum = Math.min(minimum, balance);
                    baselineMinimum = Math.min(baselineMinimum, without);
                    if (date.getDayOfMonth() == date.lengthOfMonth() || date.equals(end))
                        points.add(Map.of("date", date, "balance", money(balance), "without_credit", money(without)));
                }
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("id", variant < 0 ? "STRESS" : variant > 0 ? "OPTIMISTIC" : "BASE");
                result.put("title", variant < 0 ? "Неблагоприятный" : variant > 0 ? "Благоприятный" : "Базовый");
                result.put("revenue_change", factor - 1);
                result.put("project_revenue_factor", projectFactor);
                result.put("project_expense_factor", projectExpenseFactor);
                result.put("project_monthly_revenue", money(scenarioRevenue));
                result.put("project_monthly_expenses", money(scenarioExpenses));
                result.put("benefit_start", benefit);
                result.put("ending_balance", money(balance));
                result.put("without_credit_ending_balance", money(without));
                result.put("project_and_credit_delta", money(balance - without));
                result.put("minimum_balance", money(minimum));
                result.put("without_credit_minimum_balance", money(baselineMinimum));
                result.put("gap_amount", money(Math.max(0, -minimum)));
                result.put("first_negative_date", firstNegative);
                result.put("loan_payments", money(paid));
                result.put("remaining_principal", money(remaining));
                result.put("points", points);
                result.put("funding_feasible", fundingFeasible);
                result.put("funding_shortfall", money(fundingShortfall));
                result.put("funding_failure_date", fundingFailure);
                result.put("uncollected_project_revenue", money(fundingFeasible ? receivables : 0));
                result.put("prepaid_at_issue", money(prepaidBeforeIssue));
                result.put("launch_delay_months", candidateDelay);
                if (effect != null)
                    result.put("business_effect", effect);
                if (selected == null
                    || (variant < 0 && value(result.get("ending_balance")) < value(selected.get("ending_balance")))
                    || (variant > 0 && value(result.get("ending_balance")) > value(selected.get("ending_balance"))))
                    selected = result;
            }
            outcomes.add(selected);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "READY");
        result.put("model_version", "conditional-cashflow-v2");
        result.put("quote_id", quote.get("id"));
        result.put("purpose", draft.get("purpose"));
        result.put("goal_text", draft.get("goal_text"));
        result.put("start_date", today);
        result.put("demo_clock", !today.equals(LocalDate.now(java.time.ZoneId.of("Europe/Moscow"))));
        result.put("end_date", end);
        result.put("horizon_months", horizon);
        result.put("probabilities_available", false);
        result.put("history_method", history.seasonal() ? "LINEAR_TREND_SEASONAL_RESIDUALS" : "LINEAR_TREND_RESIDUALS");
        result.put("maturity_date", maturity);
        result.put("project_effect_known", effects != null || gap || specified);
        result.put("inputs", Map.of("project_cost", money(budget), "monthly_revenue", money(revenue),
            "monthly_expenses", money(expenses), "delay_months", delay));
        result.put("basis", Map.of("months", months.size(), "monthly_income", money(income),
            "monthly_operating_expenses", money(operatingExpenses), "revenue_variation", spread,
            "data_origin", overview.get("data_origin")));
        result.put("scenarios", outcomes);
        result.put("known_events", events);
        result.put("business_snapshot",
            Map.of("company", overview.getOrDefault("company", Map.of()), "months", months, "loans", loans));
        result.put("assumptions", List.of(
            "Это условные сценарии, а не вероятности или гарантированные границы. Горизонт охватывает погашение кредита и запуск проекта.",
            "Исторические доходы и расходы экстраполируются линейным трендом. При наличии 24 месяцев учитывается сезонная составляющая выручки. Разброс — максимальный остаток после тренда и сезонности без искусственных границ. Вероятности и инфляция не оценены.",
            "Половина операционных расходов считается переменной. Поступления и расходы вне известного календаря распределены равномерно; внутридневные разрывы не оценены.",
            "Известные платежи замещают часть месячного бюджета своего знака; если они больше бюджета, используются полностью. Расходы нового проекта считаются дополнительными.",
            "В синтетической истории выплаты прежних кредитов включены в расходы: они выделены и перенесены на даты платежей. Платежи действующих кредитов приблизительны до даты погашения.",
            "Без плана роста дополнительная выручка равна нулю. Кроме кассового разрыва, по умолчанию вся сумма кредита тратится в день выдачи; бюджет можно изменить.",
            "Для проекта: базовый сценарий использует введённые суммы; неблагоприятный — 50% доходов, 120% расходов и задержку на 2 месяца; благоприятный — 120% доходов при тех же расходах. Это допущения для проверки устойчивости."));
        if (effects != null) {
            var base = rows(effects, "scenarios").get(1);
            result.put("model_version", "business-dependencies-v2");
            result.put("inputs",
                Map.of("project_cost", base.get("project_cost"), "monthly_revenue", base.get("monthly_revenue"),
                    "monthly_expenses", base.get("monthly_expenses"), "delay_months", base.get("delay_months")));
            @SuppressWarnings("unchecked")
            var assumptions = new ArrayList<>((List<String>) result.get("assumptions"));
            assumptions.remove(assumptions.size() - 1);
            assumptions.remove(assumptions.size() - 1);
            assumptions.set(2,
                "Переменная доля расходов существующего бизнеса задана в подтверждённом плане. Потоки вне известного календаря распределены равномерно; внутридневные разрывы не оценены.");
            assumptions.add(effects.get("method").toString());
            assumptions.add(
                "Выручка поступает с заданной отсрочкой и растёт до полной загрузки; текущие затраты оплачиваются с заданным опережением, подготовительные — до запуска. Суммы оплат до выдачи отнесены на день выдачи. Налог — заданная эффективная доля положительной дополнительной выручки, а не расчёт налогового режима.");
            assumptions.add(
                "Срок запуска в пределах диапазона выбирается по худшему/лучшему конечному остатку. Диапазоны не задают корреляции. Общие ресурсы и совпадающая экономика проверяются, но семантическая независимость требует проверки пользователем.");
            assumptions.add(
                "Если не хватает денег на инвестиции, подготовку или текущие затраты проекта, он отмечается как нереализуемый: обещанный операционный эффект после этого не начисляется. Отрицательный остаток не означает автоматически предоставленный овердрафт.");
            assumptions.addAll((List<String>) effects.getOrDefault("warnings", List.of()));
            result.put("assumptions", assumptions);
            result.put("business_effects", effects);
        }
        return result;
    }
}
