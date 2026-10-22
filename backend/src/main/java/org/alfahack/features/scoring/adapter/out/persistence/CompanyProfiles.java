package org.alfahack.features.scoring.adapter.out.persistence;

import java.math.BigDecimal;
import java.util.*;
import org.alfahack.core.domain.Profile;
import org.alfahack.features.scoring.port.ProfileCatalog;
import org.alfahack.features.scoring.adapter.out.memory.SyntheticProfiles;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Company financial snapshots use the same saved transaction history as the
 * dashboard.
 */
public class CompanyProfiles implements ProfileCatalog {

    private final JdbcTemplate db;
    private final SyntheticProfiles fixtures = new SyntheticProfiles();
    public CompanyProfiles(JdbcTemplate db) {
        this.db = db;
    }

    public Profile get(String key) {
        Profile fixture = fixtures.get(key);
        String company = switch (key) {
            case "STABLE" -> "STABLE";
            case "MANUFACTURING" -> "GAP";
            case "CONSTRUCTION" -> "REJECT";
            case "MISSING_INFO", "GOOD" -> "MISSING";
            case "CAFE" -> "COUNTER";
            default -> null;
        };
        if (company == null)
            return fixture;
        var rows = db.queryForList(
            "SELECT SUBSTRING(CAST(date AS VARCHAR),1,7) AS period_key, SUM(CASE WHEN type='INCOME' THEN amount ELSE 0 END) AS income,SUM(CASE WHEN type='EXPENSE' THEN amount ELSE 0 END) AS expenses FROM transactions WHERE company_id=? GROUP BY SUBSTRING(CAST(date AS VARCHAR),1,7) ORDER BY period_key DESC LIMIT 2",
            company);
        if (rows.size() < 2)
            return new Profile(fixture.key(), fixture.sector(), fixture.businessMonths(), fixture.founders(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, fixture.monthlyDebt(), fixture.currentAssets(),
                fixture.currentLiabilities(), fixture.collateralValue(), fixture.overdueDays(), fixture.sme(),
                fixture.creditHistory());
        BigDecimal income = (BigDecimal) rows.getFirst().get("income");
        BigDecimal expenses = (BigDecimal) rows.getFirst().get("expenses");
        BigDecimal previous = (BigDecimal) rows.get(1).get("income");
        BigDecimal debt = db.queryForObject(
            "SELECT COALESCE(SUM(monthly_payment),0) FROM existing_loans WHERE company_id=?", BigDecimal.class,
            company);
        return new Profile(fixture.key(), fixture.sector(), fixture.businessMonths(), fixture.founders(), income,
            previous, income.subtract(expenses).add(debt), debt, fixture.currentAssets(), fixture.currentLiabilities(),
            fixture.collateralValue(), fixture.overdueDays(), fixture.sme(), fixture.creditHistory());
    }

    public List<Map<String, Object>> catalog() {
        return fixtures.catalog();
    }
}
