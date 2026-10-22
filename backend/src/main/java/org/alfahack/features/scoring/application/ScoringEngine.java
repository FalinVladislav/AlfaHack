package org.alfahack.features.scoring.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.alfahack.core.domain.Profile;
import org.alfahack.features.scoring.port.ProfileCatalog;
import org.alfahack.features.scoring.port.RiskSources;

/** Explainable educational policy; all thresholds are project assumptions. */
public class ScoringEngine implements org.alfahack.features.scoring.port.RiskAssessment {

    private final ProfileCatalog profiles;
    private final RiskSources sources;

    public ScoringEngine(
        ProfileCatalog profiles,
        RiskSources sources
    ) {
        this.profiles = profiles;
        this.sources = sources;
    }

    public Map<String, Object> assess(
        String key,
        BigDecimal amount,
        BigDecimal newPayment
    ) {
        return assess(key, amount, newPayment, profiles.get(key).monthlyDebt());
    }

    @Override
    public Map<String, Object> assess(
        String key,
        BigDecimal amount,
        BigDecimal newPayment,
        BigDecimal existingDebt
    ) {
        Profile original = profiles.get(key);
        Profile p = new Profile(original.key(), original.sector(), original.businessMonths(), original.founders(),
            original.monthlyRevenue(), original.previousRevenue(), original.monthlyProfit(), existingDebt,
            original.currentAssets(), original.currentLiabilities(), original.collateralValue(), original.overdueDays(),
            original.sme(), original.creditHistory());
        Map<String, Object> mocks = sources.fetch(key);
        if ("ERROR".equals(mocks.get("status")))
            return Map.of("outcome", "SOURCE_ERROR", "code", "SOURCE_UNAVAILABLE", "explanation",
                "Источник временно недоступен. Решение отложено; повторите проверку.", "sources", mocks);
        if ("ERROR".equals(((Map<?, ?>) mocks.get("turnover")).get("status")))
            return Map.of("outcome", "INSUFFICIENT_DATA", "code", "INCOME_UNKNOWN", "explanation",
                "Не хватает сведений об оборотах. Нужна финансовая выписка.", "sources", mocks);
        if (p.monthlyRevenue().signum() <= 0 || p.currentLiabilities().signum() <= 0
            || p.previousRevenue().signum() <= 0)
            return Map.of("outcome", "INSUFFICIENT_DATA", "code", "METRIC_INPUT_MISSING", "explanation",
                "Недостаточно данных для расчёта ключевых метрик.", "sources", mocks);
        double revenue = p.monthlyRevenue().doubleValue(), profit = p.monthlyProfit().doubleValue();
        double dti = p.monthlyDebt().add(newPayment).doubleValue() / revenue;
        double ltv = p.collateralValue().signum() > 0
            ? amount.doubleValue() / p.collateralValue().doubleValue()
            : Double.NaN;
        double liquidity = p.currentAssets().doubleValue() / p.currentLiabilities().doubleValue();
        double margin = profit / revenue,
            growth = (revenue - p.previousRevenue().doubleValue()) / p.previousRevenue().doubleValue();
        int dtiPoints = dti <= .25 ? 30 : dti <= .4 ? 18 : 0;
        int ltvPoints = Double.isNaN(ltv) ? 8 : ltv <= .7 ? 15 : ltv <= .9 ? 9 : 0;
        int liquidityPoints = liquidity >= 1.5 ? 20 : liquidity >= 1 ? 12 : 0;
        int marginPoints = margin >= .15 ? 20 : margin >= .08 ? 12 : 0;
        int growthPoints = growth >= .10 ? 15 : growth >= 0 ? 9 : 0;
        int score = dtiPoints + ltvPoints + liquidityPoints + marginPoints + growthPoints;
        boolean priority = p.key().equals("MANUFACTURING") || p.key().equals("IT") || p.key().equals("AGRICULTURE")
            || p.key().equals("PRIORITY_CONFLICT");
        boolean subsidy = priority && p.sme() && p.businessMonths() > 12;
        int adjusted = Math.min(100, score + (subsidy ? 5 : 0));
        String outcome, code, explanation;
        if (p.overdueDays() >= 30 || adjusted < 45 || (dti > .55 && liquidity < 1)) {
            outcome = "REJECTED";
            code = p.overdueDays() >= 30 ? "CURRENT_OVERDUE_30_DAYS" : "HIGH_RISK";
            explanation = "Высокая долговая нагрузка, слабая ликвидность или просрочка повышают риск невозврата. Льгота не отменяет ограничение по риску.";
        } else
            if (adjusted < 75 || dti > .4 || (Double.isNaN(ltv) && amount.compareTo(BigDecimal.valueOf(1000000)) > 0)) {
                outcome = "CONDITIONAL";
                code = "RISK_LIMIT";
                explanation = "Финансирование возможно при меньшей сумме или дополнительном обеспечении: нагрузка близка к порогу.";
            } else {
                outcome = "APPROVED";
                code = "POLICY_PASSED";
                explanation = "Денежный поток и ликвидность позволяют обслуживать выбранный платёж.";
            }
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("dti", metric(dti, 30, dtiPoints, "≤25%: 30; ≤40%: 18; выше: 0"));
        metrics.put("ltv",
            Double.isNaN(ltv)
                ? Map.of("value", "N/A", "weight", 15, "points", 8, "thresholds",
                    "Нет залога: 8; ≤70%: 15; ≤90%: 9; выше: 0")
                : metric(ltv, 15, ltvPoints, "≤70%: 15; ≤90%: 9; выше: 0"));
        metrics.put("current_ratio", metric(liquidity, 20, liquidityPoints, "≥1,5: 20; ≥1,0: 12; ниже: 0"));
        metrics.put("profit_margin", metric(margin, 20, marginPoints, "≥15%: 20; ≥8%: 12; ниже: 0"));
        metrics.put("revenue_growth", metric(growth, 15, growthPoints, "≥10%: 15; ≥0%: 9; ниже: 0"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inputs", Map.of("monthly_revenue", p.monthlyRevenue(), "previous_revenue", p.previousRevenue(),
            "monthly_profit", p.monthlyProfit(), "existing_monthly_debt", p.monthlyDebt(), "new_payment", newPayment,
            "current_assets", p.currentAssets(), "current_liabilities", p.currentLiabilities(),
            "collateral_value", p.collateralValue(), "overdue_days", p.overdueDays(), "requested_amount", amount));
        result.put("formulas", Map.of("dti", "(existing_monthly_debt + new_payment) / monthly_revenue",
            "ltv", "requested_amount / collateral_value", "current_ratio", "current_assets / current_liabilities",
            "profit_margin", "monthly_profit / monthly_revenue", "revenue_growth",
            "(monthly_revenue - previous_revenue) / previous_revenue"));
        result.put("stop_factors", p.overdueDays() >= 30
            ? java.util.List.of("CURRENT_OVERDUE_30_DAYS")
            : adjusted < 45
                ? java.util.List.of("HIGH_RISK")
                : dti > .55 && liquidity < 1 ? java.util.List.of("DEBT_AND_LIQUIDITY") : java.util.List.of());
        result.put("outcome", outcome);
        result.put("code", code);
        result.put("explanation", explanation);
        result.put("score", score);
        result.put("adjusted_score", adjusted);
        result.put("subsidy_applied", subsidy);
        result.put("risk_coefficient", subsidy ? 0.90 : 1.0);
        result.put("metrics", metrics);
        result.put("sources", mocks);
        result.put("profile", Map.of("key", p.key(), "sector", p.sector(), "business_months", p.businessMonths(),
            "founders", p.founders()));
        return result;
    }

    private Map<String, Object> metric(
        double value,
        int weight,
        int points,
        String thresholds
    ) {
        return Map.of("value", BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP), "weight", weight, "points",
            points, "thresholds", thresholds);
    }
}
