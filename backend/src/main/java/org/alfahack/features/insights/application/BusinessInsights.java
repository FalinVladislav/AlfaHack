package org.alfahack.features.insights.application;

import java.math.BigDecimal;
import java.util.*;
import org.alfahack.core.domain.User;
import org.alfahack.features.forecast.port.ForecastUseCases;
import org.alfahack.features.finance.port.FinanceUseCases;
import org.alfahack.features.insights.port.InsightUseCases;

public class BusinessInsights implements InsightUseCases {

    private final ForecastUseCases forecast;
    private final FinanceUseCases finance;
    public BusinessInsights(
        ForecastUseCases forecast,
        FinanceUseCases finance
    ) {
        this.forecast = forecast;
        this.finance = finance;
    }

    public Map<String, Object> cashflow(User user) {
        return finance.overview(user);
    }

    public Map<String, Object> insights(User user) {
        user.client();
        var data = finance.overview(user);
        var plan = forecast.forecast(user, "30D", "BASE");
        List<Map<String, Object>> cards = new ArrayList<>();
        BigDecimal gap = (BigDecimal) plan.get("gap_amount");
        if (gap.signum() > 0) {
            Map<String, Object> risk = new LinkedHashMap<>();
            risk.put("type", "RISK");
            risk.put("priority", 1);
            risk.put("title", "Риск кассового разрыва");
            risk.put("text", "Минимум на " + plan.get("minimum_date") + ": −" + gap.toPlainString()
                + " ₽. Проверьте платежи и даты поступлений.");
            risk.put("highlight", "Плановый дефицит " + gap.toPlainString() + " ₽; краткосрочный лимит до 300 000 ₽");
            risk.put("signal", "Плановые события и графики действующих кредитов");
            risk.put("purpose", "CASH_GAP");
            risk.put("amount", gap.min(new BigDecimal("300000")));
            risk.put("duration_days", 45);
            risk.put("term_months", 45);
            risk.put("needed_by", plan.get("first_negative_date"));
            risk.put("tone", "violet");
            risk.put("source", "ledger_events + existing_loans + loans");
            risk.put("action", "Подобрать финансирование");
            cards.add(risk);
        }
        Map<String, Object> opportunity = new LinkedHashMap<>();
        opportunity.put("type", "OPPORTUNITY");
        opportunity.put("priority", 2);
        opportunity.put("title", "Проверить возможность развития");
        @SuppressWarnings("unchecked")
        var months = (List<Map<String, Object>>) data.get("months");
        BigDecimal net = months.isEmpty() ? BigDecimal.ZERO : (BigDecimal) months.getLast().get("net");
        opportunity.put("text", "Чистый денежный поток за последний завершённый месяц: " + net.toPlainString()
            + " ₽. Сравните стоимость финансирования с плановым остатком; дополнительная выручка не предполагается.");
        opportunity.put("highlight", "Гипотеза требует оценки бизнеса");
        opportunity.put("signal", "История операций компании");
        opportunity.put("source", "transactions; синтетические данные");
        opportunity.put("action", "Рассчитать условия");
        opportunity.put("purpose", "EXPANSION");
        opportunity.put("amount", 1000000);
        opportunity.put("term_months", 24);
        opportunity.put("tone", "mint");
        if (net.signum() > 0)
            cards.add(opportunity);
        else {
            opportunity.put("type", "RISK");
            opportunity.put("priority", 1);
            opportunity.put("title", "Расходы превышают поступления");
            opportunity.put("highlight", "Дефицит по операциям " + net.negate().toPlainString() + " ₽ за "
                + (months.isEmpty() ? "неизвестный период" : months.getLast().get("month")));
            opportunity.put("purpose", "WORKING_CAPITAL");
            opportunity.put("action", "Проверить расходы и условия");
            cards.add(opportunity);
        }
        return Map.of("cards", cards, "basis", "Детерминированные сигналы по данным вашей компании", "data_origin",
            data.get("data_origin"));
    }
}
