package org.alfahack.features.forecast.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
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
    private final Supplier<LocalDate> bankDate;

    public CreditScenarios(
        FinanceUseCases finance,
        ForecastRepository repository,
        ProductCatalog products,
        String today
    ) {
        this(finance, repository, products, () -> LocalDate.parse(today));
    }

    public CreditScenarios(
        FinanceUseCases finance,
        ForecastRepository repository,
        ProductCatalog products,
        ZoneId businessZone
    ) {
        this(finance, repository, products, () -> LocalDate.now(businessZone));
    }

    private CreditScenarios(
        FinanceUseCases finance,
        ForecastRepository repository,
        ProductCatalog products,
        Supplier<LocalDate> bankDate
    ) {
        this.finance = finance;
        this.repository = repository;
        this.products = products;
        this.bankDate = bankDate;
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
        LocalDate today = bankDate.get();
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
        var months = rows(overview, "months").stream()
            .filter(m -> YearMonth.parse(m.get("month").toString()).isBefore(YearMonth.from(today)))
            .sorted(java.util.Comparator.comparing(m -> m.get("month").toString())).toList();
        months = months.subList(Math.max(0, months.size() - 12), months.size());
        if (months.size() < 3 || months.stream().anyMatch(m -> m.get("income") == null || m.get("expenses") == null)
            || months.stream().mapToDouble(m -> value(m.get("income"))).average().orElse(0) <= 0)
            return Map.of("status", "INSUFFICIENT_DATA", "quote_id", quote.get("id"), "scenarios", List.of(),
                "message", "Нужны доходы и расходы хотя бы за три полных месяца. Сценарии пока не рассчитаны.");
        double income = months.stream().mapToDouble(m -> value(m.get("income"))).average().orElseThrow();
        double historicalExpenses = months.stream().mapToDouble(m -> value(m.get("expenses"))).average().orElseThrow();
        var loans = rows(overview, "loans");
        // Synthetic history includes pre-existing debt inside expenses. Issued loans
        // are separate.
        double embeddedDebt = loans.stream().filter(l -> "EXISTING".equals(l.get("source")))
            .mapToDouble(l -> value(l.get("monthly_payment"))).sum();
        double operatingExpenses = Math.max(0, historicalExpenses - embeddedDebt);
        double spread = Math.max(.1, Math.min(.5, months.stream()
            .mapToDouble(m -> Math.abs(value(m.get("income")) / income - 1)).max().orElse(.1)));
        LocalDate issue = draft.get("needed_by") == null ? today.plusDays(1) : (LocalDate) draft.get("needed_by");
        if (issue.isBefore(today.plusDays(1)))
            throw DomainException.conflict("QUOTE_STALE", "Дата получения прошла. Обновите условия кредита");
        LocalDate end = issue.plusMonths(12);
        var events = repository.plannedEvents(user.company(), today, end);
        var schedule = rows(products.calculate(products.get((String) quote.get("product_id")),
            new BigDecimal(quote.get("amount").toString()),
            quote.get("term_months") == null ? null : ((Number) quote.get("term_months")).intValue(),
            quote.get("duration_days") == null ? null : ((Number) quote.get("duration_days")).intValue()), "schedule");
        LocalDate maturity = quote.get("duration_days") == null
            ? issue.plusMonths(((Number) quote.get("term_months")).longValue())
            : issue.plusDays(((Number) quote.get("duration_days")).longValue());
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (int variant = -1; variant <= 1; variant++) {
            double factor = 1 + variant * spread;
            double projectFactor = variant < 0 ? .5 : variant > 0 ? 1.2 : 1;
            double projectExpenseFactor = variant < 0 ? 1.2 : 1;
            Map<String, Object> effect = effects == null ? null : rows(effects, "scenarios").get(variant + 1);
            double scenarioRevenue = effect == null ? revenue * projectFactor : value(effect.get("monthly_revenue"));
            double scenarioExpenses = effect == null
                ? expenses * projectExpenseFactor
                : value(effect.get("monthly_expenses"));
            double scenarioBudget = effect == null ? budget : value(effect.get("project_cost"));
            LocalDate benefit = issue.plusMonths(effect == null
                ? delay + (variant < 0 ? 2 : 0)
                : ((Number) effect.get("delay_months")).intValue());
            double balance = value(repository.balance(user.company()).get("balance"));
            double without = balance, minimum = balance, baselineMinimum = balance, remaining = 0, paid = 0;
            LocalDate firstNegative = balance < 0 ? today : null;
            List<Map<String, Object>> points = new ArrayList<>();
            points.add(Map.of("date", today, "balance", money(balance), "without_credit", money(without)));
            var flows = new java.util.HashMap<LocalDate, Double>();
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
                double residual = (Math.max(0, income * factor * share - knownIncome)
                    - Math.max(0, operatingExpenses * (.5 + .5 * factor) * share - knownExpense)) / count;
                for (LocalDate date = start; !date.isAfter(through); date = date.plusDays(1))
                    flows.merge(date, residual, Double::sum);
                start = through.plusDays(1);
            }
            for (LocalDate date = today.plusDays(1); !date.isAfter(end); date = date.plusDays(1)) {
                double operating = flows.getOrDefault(date, 0.0);
                for (var loan : loans) {
                    LocalDate next = (LocalDate) loan.get("next_payment_date");
                    LocalDate last = (LocalDate) loan.get("end_date");
                    if (next == null || date.isBefore(next) || (last != null && date.isAfter(last)))
                        continue;
                    if (loan.get("duration_days") != null) {
                        if (date.equals(next))
                            operating -= value(loan.get("monthly_payment"));
                    } else {
                        long m = java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(next),
                            YearMonth.from(date));
                        if (date.equals(next.plusMonths(m)))
                            operating -= value(loan.get("monthly_payment"));
                    }
                }
                without += operating;
                balance += operating;
                if (date.equals(issue)) {
                    balance += amount - scenarioBudget;
                    remaining = amount;
                }
                if (!date.isBefore(benefit))
                    balance += (scenarioRevenue - scenarioExpenses) / date.lengthOfMonth();
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
            if (effect != null)
                result.put("business_effect", effect);
            outcomes.add(result);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "READY");
        result.put("model_version", "conditional-cashflow-v1");
        result.put("quote_id", quote.get("id"));
        result.put("purpose", draft.get("purpose"));
        result.put("goal_text", draft.get("goal_text"));
        result.put("start_date", today);
        result.put("end_date", end);
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
            "Это условные сценарии, а не гарантированный минимум, максимум или вероятности исходов. Горизонт — 12 месяцев после выдачи.",
            "Выручка базового сценария — средняя за полные месяцы. Разброс — максимальное отклонение от среднего, ограниченное диапазоном 10–50%. Сезонность и инфляция не оценены.",
            "Половина операционных расходов считается переменной. Поступления и расходы вне известного календаря распределены равномерно; внутридневные разрывы не оценены.",
            "Известные платежи замещают часть месячного бюджета своего знака; если они больше бюджета, используются полностью. Расходы нового проекта считаются дополнительными.",
            "В финансовой истории выплаты прежних кредитов включены в расходы: они выделены и перенесены на даты платежей. Платежи действующих кредитов приблизительны до даты погашения.",
            "Без плана роста дополнительная выручка равна нулю. Кроме кассового разрыва, по умолчанию вся сумма кредита тратится в день выдачи; бюджет можно изменить.",
            "Для проекта: базовый сценарий использует введённые суммы; неблагоприятный — 50% доходов, 120% расходов и задержку на 2 месяца; благоприятный — 120% доходов при тех же расходах. Это допущения для проверки устойчивости."));
        if (effects != null) {
            var base = rows(effects, "scenarios").get(1);
            result.put("model_version", "business-dependencies-v1");
            result.put("inputs",
                Map.of("project_cost", base.get("project_cost"), "monthly_revenue", base.get("monthly_revenue"),
                    "monthly_expenses", base.get("monthly_expenses"), "delay_months", base.get("delay_months")));
            @SuppressWarnings("unchecked")
            var assumptions = new ArrayList<>((List<String>) result.get("assumptions"));
            assumptions.remove(assumptions.size() - 1);
            assumptions.remove(assumptions.size() - 1);
            assumptions.add(effects.get("method").toString());
            assumptions.add(
                "Диапазоны проекта подтверждены пользователем. Эффект начинается после запуска и сохраняется постоянным на горизонте расчёта. Независимость компонентов, налоги и полноту затрат необходимо проверить в плане; сезонность и постепенный выход на загрузку автоматически не выводятся.");
            result.put("assumptions", assumptions);
            result.put("business_effects", effects);
        }
        return result;
    }
}
