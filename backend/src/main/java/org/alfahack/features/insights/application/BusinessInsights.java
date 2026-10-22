package org.alfahack.features.insights.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.alfahack.core.domain.Profile;
import org.alfahack.core.domain.User;
import org.alfahack.features.scoring.port.ProfileCatalog;

public class BusinessInsights implements org.alfahack.features.insights.port.InsightUseCases {

    private final ProfileCatalog profiles;
    private final LocalDate today;

    public BusinessInsights(
        ProfileCatalog profiles,
        String today
    ) {
        this.profiles = profiles;
        this.today = LocalDate.parse(today);
    }

    private Profile profile(
        User user
    ) {
        user.client();
        return profiles.get(user.company().equals("GAP") ? "MANUFACTURING" : "CAFE");
    }

    public Map<String, Object> cashflow(
        User user
    ) {
        Profile p = profile(user);
        List<Map<String, Object>> months = new ArrayList<>();
        BigDecimal totalIncome = BigDecimal.ZERO, totalExpenses = BigDecimal.ZERO;
        for (int i = 6; i >= 1; i--) {
            YearMonth month = YearMonth.from(today).minusMonths(i);
            double factor = 1 + (6 - i) * .025 + (i % 3 - 1) * .018;
            BigDecimal income = p.monthlyRevenue().multiply(BigDecimal.valueOf(factor)).setScale(2,
                RoundingMode.HALF_UP);
            BigDecimal expenses = income.subtract(p.monthlyProfit()).add(p.monthlyDebt()).max(BigDecimal.ZERO);
            months.add(Map.of("month", month.toString(), "income", income, "expenses", expenses, "net",
                income.subtract(expenses)));
            totalIncome = totalIncome.add(income);
            totalExpenses = totalExpenses.add(expenses);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("period", "Последние 6 завершённых месяцев");
        result.put("months", months);
        result.put("total_income", totalIncome);
        result.put("total_expenses", totalExpenses);
        result.put("net", totalIncome.subtract(totalExpenses));
        result.put("expense_categories",
            List.of(Map.of("name", "Поставщики и материалы", "share", 48), Map.of("name", "Команда", "share", 28),
                Map.of("name", "Аренда и логистика", "share", 16), Map.of("name", "Прочее", "share", 8)));
        result.put("data_origin", "SYNTHETIC");
        return result;
    }

}
