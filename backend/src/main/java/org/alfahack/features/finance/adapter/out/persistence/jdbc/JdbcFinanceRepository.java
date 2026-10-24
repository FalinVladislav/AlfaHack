package org.alfahack.features.finance.adapter.out.persistence.jdbc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.alfahack.features.finance.port.FinanceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static org.alfahack.core.adapter.out.persistence.jdbc.JdbcRows.row;
import static org.alfahack.core.adapter.out.persistence.jdbc.JdbcRows.rows;

@Repository
public class JdbcFinanceRepository implements FinanceRepository {

    private final JdbcTemplate db;
    public JdbcFinanceRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Map<String, Object> company(String companyId) {
        return row(db.queryForMap("SELECT * FROM companies WHERE id=?", companyId));
    }

    public List<Map<String, Object>> monthlyTotals(String companyId) {
        return rows(db.queryForList(
            "SELECT EXTRACT(YEAR FROM date) AS year_number,EXTRACT(MONTH FROM date) AS month_number,type,SUM(amount) AS amount FROM transactions WHERE company_id=? GROUP BY EXTRACT(YEAR FROM date),EXTRACT(MONTH FROM date),type ORDER BY year_number,month_number",
            companyId));
    }

    public List<Map<String, Object>> transactions(String companyId) {
        return rows(db.queryForList("SELECT * FROM transactions WHERE company_id=? ORDER BY date DESC,id", companyId));
    }

    public Map<String, Object> activity(
        String companyId,
        LocalDate from,
        LocalDate to,
        String type,
        String category,
        int page,
        int size
    ) {
        StringBuilder filter = new StringBuilder(" WHERE company_id=?");
        List<Object> args = new ArrayList<>();
        args.add(companyId);
        if (from != null) {
            filter.append(" AND date>=?");
            args.add(from);
        }
        if (to != null) {
            filter.append(" AND date<=?");
            args.add(to);
        }
        if (!"ALL".equals(type)) {
            filter.append(" AND type=?");
            args.add(type);
        }
        if (category != null && !category.isBlank()) {
            filter.append(" AND category=?");
            args.add(category);
        }
        String where = filter.toString();
        long total = db.queryForObject("SELECT COUNT(*) FROM transactions" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) (page - 1) * size);
        var items = rows(db.queryForList("SELECT id,date,amount,type,category,counterparty_id FROM transactions"
            + where + " ORDER BY date DESC,id DESC LIMIT ? OFFSET ?", pageArgs.toArray()));
        var daily = rows(db.queryForList("SELECT date,type,SUM(amount) AS amount FROM transactions"
            + where + " GROUP BY date,type ORDER BY date", args.toArray()));
        var grouped = new TreeMap<YearMonth, BigDecimal[]>();
        BigDecimal income = BigDecimal.ZERO, expenses = BigDecimal.ZERO, financing = BigDecimal.ZERO;
        for (var entry : daily) {
            var amount = (BigDecimal) entry.get("amount");
            String movementType = (String) entry.get("type");
            if ("INCOME".equals(movementType))
                income = income.add(amount);
            else
                if ("EXPENSE".equals(movementType))
                    expenses = expenses.add(amount);
                else
                    if ("FINANCING".equals(movementType))
                        financing = financing.add(amount);
            var pair = grouped.computeIfAbsent(YearMonth.from((LocalDate) entry.get("date")),
                ignored -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            int index = "INCOME".equals(movementType) ? 0 : "EXPENSE".equals(movementType) ? 1 : 2;
            pair[index] = pair[index].add(amount);
        }
        var months = new ArrayList<Map<String, Object>>();
        grouped.forEach((
            month,
            pair) -> months.add(Map.of("month", month.toString(),
                "income", pair[0], "expenses", pair[1], "financing", pair[2],
                "net", pair[0].add(pair[2]).subtract(pair[1]))));
        var categories = rows(db.queryForList("SELECT category,SUM(amount) AS amount FROM transactions"
            + where + " AND type='EXPENSE' GROUP BY category ORDER BY amount DESC", args.toArray()));
        StringBuilder categoryFilter = new StringBuilder(" WHERE company_id=? AND type='EXPENSE'");
        List<Object> categoryArgs = new ArrayList<>();
        categoryArgs.add(companyId);
        if (from != null) {
            categoryFilter.append(" AND date>=?");
            categoryArgs.add(from);
        }
        if (to != null) {
            categoryFilter.append(" AND date<=?");
            categoryArgs.add(to);
        }
        var availableCategories = db.queryForList("SELECT DISTINCT category FROM transactions"
            + categoryFilter + " ORDER BY category", String.class, categoryArgs.toArray());
        StringBuilder balanceSql = new StringBuilder("SELECT date,balance FROM balances WHERE company_id=?");
        List<Object> balanceArgs = new ArrayList<>();
        balanceArgs.add(companyId);
        if (to != null) {
            balanceSql.append(" AND date<=?");
            balanceArgs.add(to);
        }
        var balanceRows = rows(db.queryForList(balanceSql + " ORDER BY date DESC LIMIT 1", balanceArgs.toArray()));
        if (from != null) {
            balanceSql.append(" AND date>=?");
            balanceArgs.add(from);
        }
        var balances = rows(db.queryForList(balanceSql + " ORDER BY date DESC LIMIT 7", balanceArgs.toArray()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("income", income);
        result.put("expenses", expenses);
        result.put("financing", financing);
        result.put("net", income.add(financing).subtract(expenses));
        result.put("months", months);
        result.put("expense_categories", categories);
        result.put("available_categories", availableCategories);
        result.put("balance", balanceRows.isEmpty() ? null : balanceRows.getFirst().get("balance"));
        result.put("balance_date", balanceRows.isEmpty() ? null : balanceRows.getFirst().get("date"));
        result.put("balances", balances);
        return result;
    }

    public List<Map<String, Object>> balances(String companyId) {
        return rows(db.queryForList("SELECT date,balance FROM balances WHERE company_id=? ORDER BY date", companyId));
    }

    public List<Map<String, Object>> existingLoans(String companyId) {
        return rows(db.queryForList(
            "SELECT *, 'EXISTING' AS source FROM existing_loans WHERE company_id=? AND remaining_debt>0 ORDER BY next_payment_date",
            companyId));
    }

    public List<Map<String, Object>> issuedLoans(String companyId) {
        return rows(db.queryForList(
            "SELECT l.id,l.company_id,COALESCE(o.product_id,a.product_id) AS name,l.balance AS remaining_debt,l.next_payment_amount AS monthly_payment,COALESCE(o.annual_rate,a.annual_rate) AS rate,l.next_payment_date, 'ISSUED' AS source,COALESCE(o.term_months,a.term_months) AS term_months,COALESCE(o.duration_days,a.duration_days) AS duration_days,l.created_at, (SELECT MIN(e.event_date) FROM ledger_events e WHERE e.company_id=l.company_id AND e.kind='DISBURSEMENT' AND e.source=CONCAT('Выдача кредита ',l.id)) AS issue_date FROM loans l JOIN applications a ON a.id=l.application_id LEFT JOIN bank_decisions d ON d.application_id=a.id LEFT JOIN application_offers o ON o.id=d.offer_id WHERE l.company_id=? AND l.balance>0 ORDER BY l.next_payment_date",
            companyId));
    }

    public List<Map<String, Object>> products() {
        return rows(db.queryForList("SELECT * FROM loan_products ORDER BY product_id"));
    }

    public List<Map<String, Object>> needs(String companyId) {
        return rows(db.queryForList("SELECT * FROM loan_requests WHERE company_id=? ORDER BY id", companyId));
    }
}
