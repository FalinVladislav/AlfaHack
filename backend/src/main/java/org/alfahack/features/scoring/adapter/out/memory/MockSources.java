package org.alfahack.features.scoring.adapter.out.memory;

import java.util.LinkedHashMap;
import java.util.Map;
import org.alfahack.core.domain.Profile;
import org.alfahack.features.scoring.port.ProfileCatalog;

/**
 * Local deterministic stand-ins for external sources. Never makes network
 * calls.
 */
public class MockSources implements org.alfahack.features.scoring.port.RiskSources {

    private final ProfileCatalog profiles;

    public MockSources(
        ProfileCatalog profiles
    ) {
        this.profiles = profiles;
    }

    public Map<String, Object> fetch(
        String key
    ) {
        Profile p = profiles.get(key);
        Map<String, Object> result = new LinkedHashMap<>();
        if (key.equals("MOCK_FAILURE"))
            return Map.of("status", "ERROR", "code", "SOURCE_UNAVAILABLE", "retryable", true);
        result.put("credit_history", creditHistory(p));
        result.put("turnover", turnover(key, p));
        result.put("collateral", collateral(p));
        result.put("sme_registry", registry(p));
        result.put("status", "OK");
        return result;
    }

    private Map<String, Object> creditHistory(
        Profile p
    ) {
        return Map.of("status", "OK", "history", p.creditHistory(), "overdue_days", p.overdueDays(), "monthly_debt",
            p.monthlyDebt());
    }

    private Map<String, Object> turnover(
        String key,
        Profile p
    ) {
        return key.equals("MISSING_INFO")
            ? Map.of("status", "ERROR", "code", "DATA_MISSING", "retryable", false)
            : Map.of("status", "OK", "monthly_revenue", p.monthlyRevenue(), "previous_revenue", p.previousRevenue(),
                "monthly_profit", p.monthlyProfit(), "current_assets", p.currentAssets(), "current_liabilities",
                p.currentLiabilities());
    }

    private Map<String, Object> collateral(
        Profile p
    ) {
        return Map.of("status", "OK", "estimated_value", p.collateralValue(), "available",
            p.collateralValue().signum() > 0);
    }

    private Map<String, Object> registry(
        Profile p
    ) {
        return Map.of("status", "OK", "included", p.sme(), "business_months", p.businessMonths());
    }
}
