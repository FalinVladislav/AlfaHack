package org.alfahack.core.config;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.LocalDateTime;
import org.alfahack.features.products.port.ProductCatalog;
import org.alfahack.features.scoring.port.ProfileCatalog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fictional finance fixtures, inserted once per company without replacing user
 * activity.
 */
@Component
@Order(1)
public class SyntheticFinanceData implements CommandLineRunner {

    private final JdbcTemplate db;
    private final ProfileCatalog profiles;
    private final ProductCatalog products;
    private final LocalDate today;

    public SyntheticFinanceData(
        JdbcTemplate db,
        ProfileCatalog profiles,
        ProductCatalog products,
        @Value("${app.demo-today}") String today
    ) {
        this.db = db;
        this.profiles = profiles;
        this.products = products;
        this.today = LocalDate.parse(today);
    }

    @Override
    @Transactional
    public void run(String... args) {
        seedCompany("STABLE", "CAFE", "56.10", 12);
        seedCompany("GAP", "MANUFACTURING", "25.11", 28);
        for (var product : products.catalog()) {
            if (count("SELECT COUNT(*) FROM loan_products WHERE product_id=?", product.id()) == 0) {
                db.update("INSERT INTO loan_products VALUES (?,?,?,?,?,?,?,?,?)", product.id(), product.name(),
                    product.purpose(), product.rate(), product.min(), product.max(), product.minTerm(),
                    product.maxTerm(), "Профиль МСП, проверка оборотов и долговой нагрузки");
            }
        }
    }

    private void seedCompany(
        String company,
        String profileKey,
        String okved,
        int employees
    ) {
        var profile = profiles.get(profileKey);
        if (count("SELECT COUNT(*) FROM company_risk_facts WHERE company_id=?", company) == 0)
            db.update("INSERT INTO company_risk_facts VALUES (?,?,?,?,?,?,?,?)", company,
                profile.currentAssets(), profile.currentLiabilities(), profile.collateralValue(),
                profile.overdueDays(), profile.creditHistory(), "Внутренние сведения банка", LocalDateTime.now());
        db.update(
            "UPDATE companies SET company_id=id,okved=?,business_age=?,industry=?,employees=?,annual_revenue=? WHERE id=? AND company_id IS NULL",
            okved, profile.businessMonths(), profile.sector(), employees,
            profile.monthlyRevenue().multiply(BigDecimal.valueOf(12)), company);
        if (count("SELECT COUNT(*) FROM transactions WHERE company_id=?", company) == 0) {
            for (int index = 6; index >= 1; index--) {
                var month = YearMonth.from(today).minusMonths(index);
                double factor = 1 + (6 - index) * .025 + (index % 3 - 1) * .018;
                var income = profile.monthlyRevenue().multiply(BigDecimal.valueOf(factor)).setScale(2,
                    RoundingMode.HALF_UP);
                var expenses = income.subtract(profile.monthlyProfit()).add(profile.monthlyDebt());
                transaction(company, month.atDay(8), income, "INCOME", "Выручка", "CUSTOMERS");
                var supplier = expenses.multiply(new BigDecimal("0.48")).setScale(2, RoundingMode.HALF_UP);
                var team = expenses.multiply(new BigDecimal("0.28")).setScale(2, RoundingMode.HALF_UP);
                var rent = expenses.multiply(new BigDecimal("0.16")).setScale(2, RoundingMode.HALF_UP);
                transaction(company, month.atDay(12), supplier, "EXPENSE", "Поставщики и закупки", "SUPPLIERS");
                transaction(company, month.atDay(20), team, "EXPENSE", "Команда", "TEAM");
                transaction(company, month.atDay(24), rent, "EXPENSE", "Аренда и логистика", "RENT");
                transaction(company, month.atEndOfMonth(), expenses.subtract(supplier).subtract(team).subtract(rent),
                    "EXPENSE", "Прочие расходы", "OTHER");
            }
        }
        if (count("SELECT COUNT(*) FROM balances WHERE company_id=?", company) == 0) {
            BigDecimal closing = db.queryForObject("SELECT balance FROM companies WHERE id=?", BigDecimal.class,
                company);
            for (LocalDate day = today; !day.isBefore(today.minusMonths(6)); day = day.minusDays(1)) {
                db.update("INSERT INTO balances VALUES (?,?,?)", company, day, closing);
                var movement = db.queryForObject(
                    "SELECT COALESCE(SUM(CASE WHEN type='INCOME' THEN amount ELSE -amount END),0) FROM transactions WHERE company_id=? AND date=?",
                    BigDecimal.class, company, day);
                closing = closing.subtract(movement);
            }
        }
        if (count("SELECT COUNT(*) FROM existing_loans WHERE company_id=?", company) == 0) {
            boolean stable = company.equals("STABLE");
            loan(company, "EQUIPMENT", "Оборудование для бизнеса", stable ? "1460000" : "3200000",
                stable ? "90000" : "140000", "0.185", 5, 24);
            loan(company, "WORKING", "Оборотный кредит", stable ? "780000" : "1150000",
                stable ? "60000" : "80000", "0.22", 12, 15);
        }
        if (count("SELECT COUNT(*) FROM loan_requests WHERE company_id=?", company) == 0) {
            db.update("INSERT INTO loan_requests VALUES (?,?,?,?,?,?,?)", company + "-GROWTH", company,
                company.equals("STABLE") ? "Дополнительное оборудование" : "Расширение производства",
                new BigDecimal("1800000"), new BigDecimal("0.18"), new BigDecimal("0.08"), new BigDecimal("2000000"));
            db.update("INSERT INTO loan_requests VALUES (?,?,?,?,?,?,?)", company + "-STOCK", company,
                "Закупка товара с высоким спросом", new BigDecimal("650000"), new BigDecimal("0.20"),
                new BigDecimal("0.10"), new BigDecimal("750000"));
        }
    }

    private void transaction(
        String company,
        LocalDate day,
        BigDecimal amount,
        String type,
        String category,
        String counterparty
    ) {
        db.update("INSERT INTO transactions VALUES (?,?,?,?,?,?,?)", company + "-" + day + "-" + counterparty,
            company, day, amount, type, category, company + "-" + counterparty);
    }

    private void loan(
        String company,
        String key,
        String name,
        String debt,
        String payment,
        String rate,
        int nextDays,
        int months
    ) {
        db.update("INSERT INTO existing_loans VALUES (?,?,?,?,?,?,?,?)", company + "-" + key, company, name,
            new BigDecimal(debt), new BigDecimal(payment), new BigDecimal(rate), today.plusDays(nextDays),
            today.plusMonths(months));
    }

    private int count(
        String sql,
        Object... args
    ) {
        return db.queryForObject(sql, Integer.class, args);
    }
}
