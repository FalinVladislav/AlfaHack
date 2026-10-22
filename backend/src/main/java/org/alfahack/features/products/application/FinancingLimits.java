package org.alfahack.features.products.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.alfahack.core.domain.User;
import org.alfahack.core.errors.DomainException;
import org.alfahack.features.finance.port.FinanceUseCases;
import org.alfahack.features.forecast.port.ForecastUseCases;
import org.alfahack.features.products.port.FinancingLimitUseCases;
import org.alfahack.features.products.port.ProductCatalog;

/**
 * Preliminary demo exposure limits, independent of the final credit decision.
 */
public class FinancingLimits implements FinancingLimitUseCases {

    private final FinanceUseCases finance;
    private final ForecastUseCases forecast;
    private final ProductCatalog products;
    private final LocalDate today;

    public FinancingLimits(
        FinanceUseCases finance,
        ForecastUseCases forecast,
        ProductCatalog products,
        String today
    ) {
        this.finance = finance;
        this.forecast = forecast;
        this.products = products;
        this.today = LocalDate.parse(today);
    }

    public Map<String, Object> limits(
        User user,
        String purpose,
        String productId,
        LocalDate neededBy
    ) {
        user.client();
        if (!List.of("EXPANSION", "WORKING_CAPITAL", "CASH_GAP").contains(purpose))
            throw DomainException.bad("INVALID_PURPOSE", "Выберите тип кредита");
        if (productId != null && !productId.isBlank() && !products.get(productId).purpose().equals(purpose))
            throw DomainException.bad("PURPOSE_MISMATCH", "Продукт не соответствует типу кредита");
        LocalDate issue = neededBy == null ? today.plusDays(1) : neededBy;
        if (issue.isBefore(today.plusDays(1)) || issue.isAfter(today.plusDays(120)))
            throw DomainException.bad("INVALID_FINANCING_DATE", "Выберите дату получения в ближайшие 120 дней");
        var overview = finance.overview(user);
        var months = maps(overview.get("months")).stream()
            .filter(m -> YearMonth.parse(String.valueOf(m.get("month"))).isBefore(YearMonth.from(today)))
            .sorted(java.util.Comparator.comparing(m -> String.valueOf(m.get("month")))).toList();
        var recent = months.subList(Math.max(0, months.size() - 3), months.size());
        BigDecimal income = recent.isEmpty()
            ? BigDecimal.ZERO
            : recent.stream().map(m -> amount(m.get("income")))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(recent.size()), 2, RoundingMode.DOWN)
                .min(amount(recent.getLast().get("income")));
        var loans = maps(overview.get("loans"));
        BigDecimal monthlyDebt = loans.stream().map(l -> amount(l.get("monthly_payment"))).reduce(BigDecimal.ZERO,
            BigDecimal::add);
        BigDecimal outstanding = loans.stream().map(l -> amount(l.get("remaining_debt"))).reduce(BigDecimal.ZERO,
            BigDecimal::add);
        BigDecimal paymentBudget = income.multiply(new BigDecimal("0.40")).subtract(monthlyDebt).max(BigDecimal.ZERO);
        BigDecimal exposure = switch (purpose) {
            case "EXPANSION" -> income.multiply(BigDecimal.valueOf(6)).subtract(outstanding).max(BigDecimal.ZERO);
            case "WORKING_CAPITAL" -> income.multiply(new BigDecimal("0.80"));
            default -> income.multiply(new BigDecimal("0.25"));
        };
        var company = object(overview.get("company"));
        int age = company.get("business_age") instanceof Number n ? n.intValue() : 0;
        var plan = purpose.equals("CASH_GAP") ? forecast.forecast(user, "6M", "BASE") : Map.<String, Object>of();
        var productLimits = new ArrayList<Map<String, Object>>();
        boolean enoughData = recent.size() >= 3 && income.signum() > 0;
        for (var product : products.catalog()) {
            if (!product.purpose().equals(purpose)
                || (productId != null && !productId.isBlank() && !product.id().equals(productId)))
                continue;
            var terms = new ArrayList<Map<String, Object>>();
            int maxTerm = Math.min(product.maxTerm(),
                age >= 12
                    ? product.maxTerm()
                    : purpose.equals("EXPANSION") ? 24 : purpose.equals("WORKING_CAPITAL") ? 6 : 30);
            if (enoughData && paymentBudget.signum() > 0) {
                for (int term = product.minTerm(); term <= maxTerm; term++) {
                    boolean days = product.unit().equals("DAYS");
                    BigDecimal cap = product.max().min(exposure);
                    var maxQuote = products.calculate(product, product.max(), days ? null : term, days ? term : null);
                    if (!days) {
                        cap = cap.min(product.max().multiply(paymentBudget)
                            .divide(amount(maxQuote.get("monthly_payment")), 2, RoundingMode.DOWN));
                    } else {
                        BigDecimal balance = amount(company.get("balance"));
                        for (var event : maps(plan.get("events")))
                            if (!LocalDate.parse(String.valueOf(event.get("event_date"))).isAfter(issue.plusDays(term)))
                                balance = balance.add(amount(event.get("amount")));
                        // Principal from disbursement cancels principal on repayment; baseline must
                        // cover interest.
                        BigDecimal interestBudget = balance.max(BigDecimal.ZERO).min(paymentBudget);
                        cap = cap.min(product.max().multiply(interestBudget).divide(amount(maxQuote.get("interest")), 2,
                            RoundingMode.DOWN));
                    }
                    cap = cap.divide(BigDecimal.valueOf(1000), 0, RoundingMode.DOWN).multiply(BigDecimal.valueOf(1000));
                    if (cap.compareTo(product.min()) >= 0)
                        terms.add(Map.of("term", term, "min_amount", product.min(), "max_amount", cap));
                }
            }
            productLimits.add(Map.of("product_id", product.id(), "name", product.name(), "term_unit", product.unit(),
                "terms", terms));
        }
        boolean available = productLimits.stream().anyMatch(p -> !((List<?>) p.get("terms")).isEmpty());
        var result = new LinkedHashMap<String, Object>();
        result.put("status", !enoughData ? "INSUFFICIENT_DATA" : available ? "AVAILABLE" : "NO_CAPACITY");
        result.put("purpose", purpose);
        result.put("products", productLimits);
        result.put("amount_step", 1000);
        result.put("basis", Map.of("monthly_income", income, "existing_monthly_payments", monthlyDebt,
            "monthly_payment_budget", paymentBudget, "business_age_months", age, "history_months", recent.size()));
        result.put("note", !enoughData
            ? "Для определения диапазона нужны поступления за три завершённых месяца."
            : !available
                ? "По текущим данным доступного диапазона нет. Уточните финансовые сведения или дату получения."
                : "Диапазон учитывает обороты, действующие кредиты, возраст бизнеса и условия продукта. Окончательное решение — после проверки.");
        result.put("policy_version", "preliminary-limits-v1");
        return result;
    }

    private static BigDecimal amount(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }
}
