package org.alfahack.features.forecast.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
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
    private final ZoneId businessZone;

    public Forecast(
        ForecastRepository repository,
        String today,
        String businessZone
    ) {
        this.repository = repository;
        this.today = LocalDate.parse(today);
        this.businessZone = ZoneId.of(businessZone);
    }

    public Map<String, Object> dashboard(
        User user
    ) {
        user.client();
        Map<String, Object> company = repository.company(user.company());
        Map<String, Object> forecast = forecast(user, "30D", "BASE");
        return Map.of("company", company, "demo_today", today,
            "business_today", LocalDate.now(businessZone), "forecast", forecast, "news",
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
        LocalDate today = LocalDate.now(businessZone);
        int days = horizon.equals("30D") ? 30 : 183;
        BigDecimal balance = (BigDecimal) repository.balance(user.company()).get("balance");
        var events = repository.plannedEvents(user.company(), today, today.plusDays(days));
        List<Map<String, Object>> points = new ArrayList<>();
        LocalDate firstNegative = null, minDate = null;
        BigDecimal min = balance;
        for (int day = 1; day <= days; day++) {
            LocalDate date = today.plusDays(day);
            for (var event : events)
                if (((LocalDate) event.get("event_date")).equals(date))
                    balance = balance.add((BigDecimal) event.get("amount"));
            if (balance.signum() < 0 && firstNegative == null)
                firstNegative = date;
            if (balance.compareTo(min) < 0) {
                min = balance;
                minDate = date;
            }
            if (horizon.equals("30D") || date.getDayOfMonth() == date.lengthOfMonth() || day == days)
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
            ? "В данных нет оценочных операций; стрессовый ряд совпадает с базовым. Учтены только известные плановые события."
            : "Учтены только известные плановые события; будущая нерегулярная выручка не прогнозируется.");
        return result;
    }

    public Map<String, Object> analysis(
        User user,
        Map<String, Object> quote,
        String horizon,
        String scenario
    ) {
        LocalDate today = LocalDate.now(businessZone);
        Map<String, Object> baseline = forecast(user, horizon, scenario);
        var draft = repository.draft((String) quote.get("draft_id"));
        if (((Number) draft.get("version")).intValue() != ((Number) quote.get("draft_version")).intValue())
            throw DomainException.conflict("QUOTE_STALE", "Расчёт устарел. Сначала обновите варианты");
        String purpose = (String) draft.get("purpose");
        BigDecimal amount = (BigDecimal) quote.get("amount");
        LocalDate issue = draft.get("needed_by") == null ? today.plusDays(1) : ((LocalDate) draft.get("needed_by"));
        int days = horizon.equals("30D") ? 30 : 183;
        List<Map<String, Object>> noCredit = new ArrayList<>(), withCredit = new ArrayList<>();
        BigDecimal balance = (BigDecimal) repository.analysisBalance(user.company()).get("balance");
        var events = repository.analysisEvents(user.company(), today, today.plusDays(days));
        BigDecimal delta = BigDecimal.ZERO;
        for (int day = 1; day <= days; day++) {
            LocalDate date = today.plusDays(day);
            for (var event : events)
                if (((LocalDate) event.get("event_date")).equals(date))
                    balance = balance.add((BigDecimal) event.get("amount"));
            if (date.equals(issue)) {
                delta = delta.add(amount);
                if (!purpose.equals("CASH_GAP"))
                    delta = delta.subtract(amount);
            }
            if (quote.get("duration_days") != null) {
                if (date.equals(issue.plusDays(((Number) quote.get("duration_days")).longValue())))
                    delta = delta.subtract((BigDecimal) quote.get("total_repay"));
            } else {
                int months = ((Number) quote.get("term_months")).intValue();
                for (int m = 1; m <= months; m++)
                    if (date.equals(issue.plusMonths(m))) {
                        BigDecimal payment = (BigDecimal) quote.get("monthly_payment");
                        if (m == months)
                            payment = ((BigDecimal) quote.get("total_repay"))
                                .subtract(payment.multiply(BigDecimal.valueOf(months - 1)));
                        delta = delta.subtract(payment);
                    }
            }
            if (horizon.equals("30D") || date.getDayOfMonth() == date.lengthOfMonth() || day == days) {
                noCredit.add(Map.of("date", date, "balance", balance));
                withCredit.add(Map.of("date", date, "balance", balance.add(delta)));
            }
        }
        return Map.of("status", "PLAN_ONLY", "horizon", horizon, "scenario", scenario, "without_credit", noCredit,
            "with_credit", withCredit, "loan_total_repay", quote.get("total_repay"), "assumptions",
            purpose.equals("CASH_GAP")
                ? "Плановые расходы уже учтены в исходном ряду. Добавлены зачисление и возврат одного транша."
                : "Вся сумма кредита считается потраченной в день выдачи. Дополнительная выручка не предполагается.",
            "forecast_assumptions", baseline.get("assumptions"));
    }
}
