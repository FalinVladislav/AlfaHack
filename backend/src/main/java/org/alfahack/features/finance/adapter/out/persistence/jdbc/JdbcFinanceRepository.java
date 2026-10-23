package org.alfahack.features.finance.adapter.out.persistence.jdbc;

import java.util.List;
import java.util.Map;
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

    public List<Map<String, Object>> transactions(String companyId) {
        return rows(db.queryForList("SELECT * FROM transactions WHERE company_id=? ORDER BY date DESC,id", companyId));
    }

    public List<Map<String, Object>> balances(String companyId) {
        return rows(db.queryForList("SELECT date,balance FROM balances WHERE company_id=? ORDER BY date", companyId));
    }

    public List<Map<String, Object>> existingLoans(String companyId) {
        return rows(db.queryForList(
            "SELECT *, 'EXISTING' AS source FROM existing_loans WHERE company_id=? ORDER BY next_payment_date",
            companyId));
    }

    public List<Map<String, Object>> issuedLoans(String companyId) {
        return rows(db.queryForList(
            "SELECT l.id,l.company_id,COALESCE(o.product_id,a.product_id) AS name,l.balance AS remaining_debt,l.next_payment_amount AS monthly_payment,COALESCE(o.annual_rate,a.annual_rate) AS rate,l.next_payment_date, 'ISSUED' AS source,COALESCE(o.term_months,a.term_months) AS term_months,COALESCE(o.duration_days,a.duration_days) AS duration_days,l.created_at, (SELECT MIN(e.event_date) FROM ledger_events e WHERE e.company_id=l.company_id AND e.kind='DISBURSEMENT' AND e.source=CONCAT('Выдача кредита ',l.id)) AS issue_date FROM loans l JOIN applications a ON a.id=l.application_id LEFT JOIN bank_decisions d ON d.application_id=a.id LEFT JOIN application_offers o ON o.id=d.offer_id WHERE l.company_id=? ORDER BY l.next_payment_date",
            companyId));
    }

    public List<Map<String, Object>> products() {
        return rows(db.queryForList("SELECT * FROM loan_products ORDER BY product_id"));
    }

    public List<Map<String, Object>> needs(String companyId) {
        return rows(db.queryForList("SELECT * FROM loan_requests WHERE company_id=? ORDER BY id", companyId));
    }
}
