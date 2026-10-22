package org.alfahack.features.forecast.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.alfahack.core.domain.User;
import org.alfahack.core.errors.DomainException;
import org.alfahack.features.forecast.port.ForecastRepository;

public class Forecast implements org.alfahack.features.forecast.port.ForecastUseCases {

    private final ForecastRepository repository;
    private final LocalDate today;

    public Forecast(
        ForecastRepository repository,
        String today
    ) {
        this.repository = repository;
        this.today = LocalDate.parse(today);
    }

    public Map<String, Object> dashboard(
        User user
    ) {
        user.client();
        Map<String, Object> company = repository.company(user.company());
        Map<String, Object> forecast = forecast(user, "30D", "BASE");
        return Map.of("company", company, "demo_today", today, "forecast", forecast, "news",
            List.of(
                Map.of("title", "Как выбрать срок финансирования", "text",
                    "Сравните платёж и общую стоимость для разных сроков."),
                Map.of("title", "Что происходит после подачи заявки", "text",
                    "Банк проверяет сведения и при необходимости запрашивает документы.")));
    }

    public Map<String, Object> forecast(
        User user,
        String horizon,
        String scenario
    ) {
        user.client();
        if (!List.of("30D", "6M").contains(horizon) || !List.of("BASE", "STRESS").contains(scenario))
            throw DomainException.bad("INVALID_SCENARIO", "Выберите доступный горизонт и сценарий");
        int days = horizon.equals("30D") ? 30 : 183;
        BigDecimal balance = (BigDecimal) repository.balance(user.company()).get("balance");
        var events = repository.plannedEvents(user.company(), today, today.plusDays(days));
        List<Map<String, Object>> points = new ArrayList<>();
        LocalDate firstNegative = null, minDate = today;
        BigDecimal min = balance;
        for (int day = 1; day <= days; day++) {
            LocalDate date = today.plusDays(day);
            for (var event : events)
                if (((LocalDate) event.get("event_date")).equals(date))
                    balance = balance.add(scenarioAmount(event, scenario));
            if (balance.signum() < 0 && firstNegative == null)
                firstNegative = date;
            if (balance.compareTo(min) < 0) {
                min = balance;
                minDate = date;
            }
            points.add(Map.of("date", date, "balance", balance));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PLAN_ONLY");
        result.put("model_version", "known-events-v1");
        result.put("scenario", scenario);
        result.put("horizon", horizon);
        result.put("points", points);
        result.put("first_negative_date", firstNegative);
        result.put("minimum_date", minDate);
        result.put("minimum_balance", min);
        result.put("gap_amount", min.signum() < 0 ? min.negate() : BigDecimal.ZERO.setScale(2));
        result.put("events", events);
        result.put("assumptions", scenario.equals("STRESS")
            ? "Стресс: ожидаемые поступления снижены на 20%; обязательные платежи не меняются."
            : "Учтены только известные плановые события; будущая нерегулярная выручка не прогнозируется.");
        return result;
    }

    public Map<String, Object> analysis(
        User user,
        Map<String, Object> quote,
        String horizon,
        String scenario
    ) {
        if (!List.of("30D", "6M", "FULL").contains(horizon) || !List.of("BASE", "STRESS").contains(scenario))
            throw DomainException.bad("INVALID_SCENARIO", "Неизвестный сценарий");
        Map<String, Object> baseline = forecast(user, horizon.equals("FULL") ? "6M" : horizon, scenario);
        var draft = repository.draft((String) quote.get("draft_id"));
        if (((Number) draft.get("version")).intValue() != ((Number) quote.get("draft_version")).intValue())
            throw DomainException.conflict("QUOTE_STALE", "Расчёт устарел. Сначала обновите варианты");
        String purpose = (String) draft.get("purpose");
        BigDecimal amount = (BigDecimal) quote.get("amount");
        LocalDate issue = draft.get("needed_by") == null ? today.plusDays(1) : ((LocalDate) draft.get("needed_by"));
        int termDays = quote.get("duration_days") != null
            ? ((Number) quote.get("duration_days")).intValue()
            : (int) java.time.temporal.ChronoUnit.DAYS.between(issue,
                issue.plusMonths(((Number) quote.get("term_months")).intValue()));
        int days = horizon.equals("FULL")
            ? (int) java.time.temporal.ChronoUnit.DAYS.between(today, issue) + termDays
            : horizon.equals("30D") ? 30 : 183;
        List<Map<String, Object>> payments = new ArrayList<>();
        BigDecimal minWithout = (BigDecimal) baseline.get("minimum_balance"),
            minWith = (BigDecimal) repository.analysisBalance(user.company()).get("balance");
        List<Map<String, Object>> noCredit = new ArrayList<>(), withCredit = new ArrayList<>();
        BigDecimal balance = (BigDecimal) repository.analysisBalance(user.company()).get("balance");
        var events = repository.analysisEvents(user.company(), today, today.plusDays(days));
        minWithout = balance;
        LocalDate minWithoutDate = today, minWithDate = today;
        BigDecimal delta = BigDecimal.ZERO;
        for (int day = 1; day <= days; day++) {
            LocalDate date = today.plusDays(day);
            for (var event : events)
                if (((LocalDate) event.get("event_date")).equals(date))
                    balance = balance.add(scenarioAmount(event, scenario));
            if (date.equals(issue)) {
                delta = delta.add(amount);
                if (!purpose.equals("CASH_GAP"))
                    delta = delta.subtract(amount);
            }
            if (quote.get("duration_days") != null) {
                if (date.equals(issue.plusDays(((Number) quote.get("duration_days")).longValue()))) {
                    delta = delta.subtract((BigDecimal) quote.get("total_repay"));
                    payments.add(Map.of("date", date, "amount", quote.get("total_repay")));
                }
            } else {
                int months = ((Number) quote.get("term_months")).intValue();
                for (int m = 1; m <= months; m++)
                    if (date.equals(issue.plusMonths(m))) {
                        BigDecimal payment = (BigDecimal) quote.get("monthly_payment");
                        if (m == months)
                            payment = ((BigDecimal) quote.get("total_repay"))
                                .subtract(payment.multiply(BigDecimal.valueOf(months - 1)));
                        delta = delta.subtract(payment);
                        payments.add(Map.of("date", date, "amount", payment));
                    }
            }
            if (balance.compareTo(minWithout) < 0) {
                minWithout = balance;
                minWithoutDate = date;
            }
            if (balance.add(delta).compareTo(minWith) < 0) {
                minWith = balance.add(delta);
                minWithDate = date;
            }
            {
                noCredit.add(Map.of("date", date, "balance", balance));
                withCredit.add(Map.of("date", date, "balance", balance.add(delta)));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "PLAN_ONLY");
        result.put("horizon", horizon);
        result.put("scenario", scenario);
        result.put("without_credit", noCredit);
        result.put("with_credit", withCredit);
        result.put("minimum_without", minWithout);
        result.put("minimum_with", minWith);
        result.put("minimum_without_date", minWithoutDate);
        result.put("minimum_with_date", minWithDate);
        result.put("negative_dates", withCredit.stream().filter(p -> ((BigDecimal) p.get("balance")).signum() < 0)
            .map(p -> p.get("date")).toList());
        result.put("gap_resolved", minWithout.signum() < 0 && minWith.signum() >= 0);
        result.put("issue_date", issue);
        result.put("payments", payments);
        result.put("events", events);
        result.put("loan_total_repay", quote.get("total_repay"));
        result.put("assumptions",
            purpose.equals("CASH_GAP")
                ? "Учтены выдача, плановые расходы и полный возврат транша с процентами."
                : "Вся сумма потрачена в день выдачи. Новая выручка не предполагается.");
        result.put("forecast_assumptions",
            "План только по известным событиям и действующим кредитам. За пределами плана поступлений нет; длинный горизонт — консервативный сценарий, не предсказание. Платёжеспособность проверяется отдельно по месячному свободному потоку на весь срок при неизменных показателях.");
        return result;
    }

    private BigDecimal scenarioAmount(
        Map<String, Object> event,
        String scenario
    ) {
        BigDecimal value = (BigDecimal) event.get("amount");
        return scenario.equals("STRESS") && value.signum() > 0 && "EXPECTED".equals(event.get("kind"))
            ? value.multiply(new BigDecimal("0.8")).setScale(2, java.math.RoundingMode.HALF_UP)
            : value;
    }
}
