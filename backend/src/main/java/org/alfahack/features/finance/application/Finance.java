package org.alfahack.features.finance.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.alfahack.core.domain.User;
import org.alfahack.core.errors.DomainException;
import org.alfahack.features.finance.port.FinanceRepository;
import org.alfahack.features.finance.port.FinanceUseCases;

public class Finance implements FinanceUseCases {

    private final FinanceRepository repository;
    public Finance(FinanceRepository repository) {
        this.repository = repository;
    }

    public Map<String, Object> overview(User user) {
        user.client();
        var transactions = repository.transactions(user.company());
        var grouped = new TreeMap<YearMonth, BigDecimal[]>();
        for (var transaction : transactions) {
            var month = YearMonth.from((LocalDate) transaction.get("date"));
            var totals = grouped.computeIfAbsent(month, key -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            int index = "INCOME".equals(transaction.get("type")) ? 0 : 1;
            totals[index] = totals[index].add((BigDecimal) transaction.get("amount"));
        }
        List<Map<String, Object>> months = new ArrayList<>();
        grouped.forEach((
            month,
            amounts) -> months.add(Map.of("month", month.toString(), "income", amounts[0],
                "expenses", amounts[1], "net", amounts[0].subtract(amounts[1]))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("company", repository.company(user.company()));
        result.put("months", months);
        result.put("transactions", transactions);
        result.put("balances", repository.balances(user.company()));
        result.put("loans", loans(user));
        result.put("products", repository.products());
        result.put("needs", repository.needs(user.company()));
        result.put("data_origin", "SYNTHETIC");
        return result;
    }

    public Map<String, Object> summary(User user) {
        user.client();
        var grouped = new TreeMap<YearMonth, BigDecimal[]>();
        for (var row : repository.monthlyTotals(user.company())) {
            var month = YearMonth.of(((Number) row.get("year_number")).intValue(),
                ((Number) row.get("month_number")).intValue());
            var pair = grouped.computeIfAbsent(month, ignored -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            int index = "INCOME".equals(row.get("type")) ? 0 : 1;
            pair[index] = pair[index].add((BigDecimal) row.get("amount"));
        }
        var months = new ArrayList<Map<String, Object>>();
        grouped.forEach((
            month,
            pair) -> months.add(Map.of("month", month.toString(),
                "income", pair[0], "expenses", pair[1], "net", pair[0].subtract(pair[1]))));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("company", repository.company(user.company()));
        result.put("months", months);
        result.put("loans", loans(user));
        result.put("products", repository.products());
        result.put("needs", repository.needs(user.company()));
        return result;
    }

    public List<Map<String, Object>> loans(User user) {
        user.client();
        List<Map<String, Object>> result = new ArrayList<>(repository.existingLoans(user.company()));
        for (var loan : repository.issuedLoans(user.company())) {
            var issued = (LocalDate) loan.get("issue_date");
            if (issued != null) {
                LocalDate maturity = loan.get("duration_days") != null
                    ? issued.plusDays(((Number) loan.get("duration_days")).longValue())
                    : issued.plusMonths(((Number) loan.get("term_months")).longValue());
                loan.put("end_date", maturity);
            }
            result.add(loan);
        }
        result.sort(Comparator.comparing(loan -> (LocalDate) loan.get("next_payment_date")));
        return result;
    }

    public Map<String, Object> activity(
        User user,
        String from,
        String to,
        String type,
        String category,
        int page,
        int size
    ) {
        user.client();
        if (!List.of("ALL", "INCOME", "EXPENSE").contains(type))
            throw DomainException.bad("INVALID_TYPE", "Неизвестный тип операции");
        LocalDate start, end;
        try {
            start = from == null || from.isBlank() ? null : LocalDate.parse(from);
            end = to == null || to.isBlank() ? null : LocalDate.parse(to);
        } catch (RuntimeException error) {
            throw DomainException.bad("INVALID_PERIOD", "Проверьте даты периода");
        }
        if (start != null && end != null && start.isAfter(end))
            throw DomainException.bad("INVALID_PERIOD", "Начало периода позже окончания");
        if (category != null && category.length() > 120)
            throw DomainException.bad("INVALID_CATEGORY", "Слишком длинная категория");
        return repository.activity(user.company(), start, end, type, category,
            Math.max(1, page), Math.max(1, Math.min(50, size)));
    }
}
