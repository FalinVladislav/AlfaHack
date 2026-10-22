package org.alfahack.features.scoring.adapter.out.memory;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.alfahack.core.domain.Profile;
import org.alfahack.core.errors.DomainException;

/**
 * Reproducible, fictional SME data. No personal or registry identifiers are
 * generated.
 */
public class SyntheticProfiles implements org.alfahack.features.scoring.port.ProfileCatalog {

    private static BigDecimal n(
        long value
    ) {
        return BigDecimal.valueOf(value);
    }
    private static final Map<String, Profile> CASES = create();
    private static Map<String, Profile> create() {
        Map<String, Profile> cases = new LinkedHashMap<>();
        cases.put("RETAIL", new Profile("RETAIL", "Розница", 30, 2, n(1900000), n(1700000), n(320000), n(90000),
            n(2300000), n(1000000), n(2600000), 0, true, "CLEAN"));
        cases.put("MANUFACTURING", new Profile("MANUFACTURING", "Производство", 48, 3, n(3600000), n(3100000),
            n(600000), n(220000), n(4500000), n(2200000), n(6500000), 0, true, "CLEAN"));
        cases.put("IT", new Profile("IT", "IT-услуги", 26, 2, n(2700000), n(2100000), n(720000), n(50000), n(1750000),
            n(650000), n(0), 0, true, "CLEAN"));
        cases.put("CAFE", new Profile("CAFE", "Общепит", 18, 2, n(1250000), n(1200000), n(190000), n(150000), n(900000),
            n(750000), n(0), 0, true, "CLEAN"));
        cases.put("LOGISTICS", new Profile("LOGISTICS", "Логистика", 38, 2, n(2200000), n(2450000), n(270000),
            n(180000), n(1300000), n(1000000), n(3000000), 0, true, "CLEAN"));
        cases.put("AGRICULTURE", new Profile("AGRICULTURE", "Сельское хозяйство", 42, 3, n(1700000), n(1500000),
            n(280000), n(80000), n(2200000), n(900000), n(4000000), 0, true, "CLEAN"));
        cases.put("CONSTRUCTION", new Profile("CONSTRUCTION", "Стройка", 9, 2, n(1500000), n(1750000), n(80000),
            n(360000), n(700000), n(950000), n(700000), 45, true, "OVERDUE"));
        return java.util.Collections.unmodifiableMap(cases);
    }

    public Profile get(
        String key
    ) {
        if (key.equals("PRIORITY_CONFLICT")) {
            Profile p = CASES.get("MANUFACTURING");
            return new Profile(key, p.sector(), p.businessMonths(), p.founders(), p.monthlyRevenue(),
                p.previousRevenue(), p.monthlyProfit(), p.monthlyDebt(), p.currentAssets(), p.currentLiabilities(),
                p.collateralValue(), 45, p.sme(), "OVERDUE");
        }
        String canonical = switch (key) {
            case "GOOD", "MISSING_INFO", "MOCK_FAILURE" -> "RETAIL";
            case "GAP" -> "MANUFACTURING";
            case "COUNTEROFFER" -> "CAFE";
            case "HIGH_RISK" -> "CONSTRUCTION";
            default -> key;
        };
        Profile result = CASES.get(canonical);
        if (result == null)
            throw DomainException.bad("UNKNOWN_PROFILE", "Неизвестный синтетический профиль");
        return result;
    }

    public List<Map<String, Object>> catalog() {
        return CASES.values().stream()
            .map(p -> Map.<String, Object>of("key", p.key(), "sector", p.sector(), "business_months",
                p.businessMonths(), "founders", p.founders(), "monthly_revenue", p.monthlyRevenue(), "monthly_profit",
                p.monthlyProfit()))
            .toList();
    }
}
