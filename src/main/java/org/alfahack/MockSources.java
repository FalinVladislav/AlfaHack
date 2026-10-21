package org.alfahack;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Local deterministic stand-ins for external sources. Never makes network calls. */
@Component
public class MockSources {
    private final SyntheticProfiles profiles;
    public MockSources(SyntheticProfiles profiles) { this.profiles=profiles; }
    public Map<String,Object> fetch(String key) {
        SyntheticProfiles.Profile p=profiles.get(key);
        Map<String,Object> result=new LinkedHashMap<>();
        if (key.equals("MOCK_FAILURE")) return Map.of("status","ERROR","code","SOURCE_UNAVAILABLE","retryable",true);
        result.put("credit_history",creditHistory(p));
        result.put("turnover",turnover(key,p));
        result.put("collateral",collateral(p));
        result.put("sme_registry",registry(p));
        result.put("status","OK");
        return result;
    }
    private Map<String,Object> creditHistory(SyntheticProfiles.Profile p){return Map.of("status","OK","history",p.creditHistory(),"overdue_days",p.overdueDays(),"monthly_debt",p.monthlyDebt());}
    private Map<String,Object> turnover(String key,SyntheticProfiles.Profile p){return key.equals("MISSING_INFO")?Map.of("status","ERROR","code","DATA_MISSING","retryable",false):Map.of("status","OK","monthly_revenue",p.monthlyRevenue(),"previous_revenue",p.previousRevenue(),"monthly_profit",p.monthlyProfit(),"current_assets",p.currentAssets(),"current_liabilities",p.currentLiabilities());}
    private Map<String,Object> collateral(SyntheticProfiles.Profile p){return Map.of("status","OK","estimated_value",p.collateralValue(),"available",p.collateralValue().signum()>0);}
    private Map<String,Object> registry(SyntheticProfiles.Profile p){return Map.of("status","OK","included",p.sme(),"business_months",p.businessMonths());}
}
