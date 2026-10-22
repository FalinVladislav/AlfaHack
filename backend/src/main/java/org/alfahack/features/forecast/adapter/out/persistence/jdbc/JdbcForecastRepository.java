package org.alfahack.features.forecast.adapter.out.persistence.jdbc;

import static org.alfahack.core.adapter.out.persistence.jdbc.JdbcRows.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.alfahack.features.forecast.port.ForecastRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcForecastRepository implements ForecastRepository {

    private final JdbcTemplate db;

    public JdbcForecastRepository(
        JdbcTemplate db
    ) {
        this.db = db;
    }

    @Override
    public Map<String, Object> company(
        String companyId
    ) {
        return row(db.queryForMap("SELECT * FROM companies WHERE id=?", companyId));
    }

    @Override
    public Map<String, Object> balance(
        String companyId
    ) {
        return row(db.queryForMap("SELECT balance FROM companies WHERE id=?", companyId));
    }

    @Override
    public List<Map<String, Object>> plannedEvents(
        String companyId,
        LocalDate after,
        LocalDate through
    ) {
        var events = new java.util.ArrayList<>(rows(db.queryForList(
            "SELECT event_date,amount,kind,source FROM ledger_events WHERE company_id=? AND event_date>? AND event_date<=? AND kind<>'DISBURSEMENT' ORDER BY event_date",
            companyId, after, through)));
        for (var loan : rows(db.queryForList(
            "SELECT monthly_payment,next_payment_date,end_date,name FROM existing_loans WHERE company_id=?",
            companyId))) {
            LocalDate first = (LocalDate) loan.get("next_payment_date");
            for (int m = 0; !first.plusMonths(m).isAfter(through); m++) {
                LocalDate day = first.plusMonths(m);
                if (day.isAfter(after) && !day.isAfter((LocalDate) loan.get("end_date")))
                    events.add(Map.of("event_date", day, "amount", ((BigDecimal) loan.get("monthly_payment")).negate(),
                        "kind", "DEBT_PAYMENT", "source", loan.get("name")));
            }
        }
        for (var loan : rows(db.queryForList(
            "SELECT l.next_payment_date,l.next_payment_amount AS next_payment,a.* FROM loans l JOIN applications a ON a.id=l.application_id WHERE l.company_id=?",
            companyId))) {
            LocalDate first = (LocalDate) loan.get("next_payment_date");
            int count = loan.get("duration_days") != null ? 1 : ((Number) loan.get("term_months")).intValue();
            for (int m = 0; m < count && !first.plusMonths(m).isAfter(through); m++) {
                LocalDate day = first.plusMonths(m);
                BigDecimal due = m == count - 1
                    ? ((BigDecimal) loan.get("total_repay"))
                        .subtract(((BigDecimal) loan.get("next_payment")).multiply(BigDecimal.valueOf(count - 1)))
                    : (BigDecimal) loan.get("next_payment");
                if (day.isAfter(after))
                    events.add(Map.of("event_date", day, "amount", due.negate(), "kind", "DEBT_PAYMENT", "source",
                        "Выданный кредит"));
            }
        }
        events.sort(java.util.Comparator.comparing(e -> (LocalDate) e.get("event_date")));
        return events;
    }

    @Override
    public Map<String, Object> draft(
        String draftId
    ) {
        return row(db.queryForMap("SELECT purpose,needed_by,version FROM drafts WHERE id=?", draftId));
    }

    @Override
    public Map<String, Object> analysisBalance(
        String companyId
    ) {
        return row(db.queryForMap("SELECT balance FROM companies WHERE id=?", companyId));
    }

    @Override
    public List<Map<String, Object>> analysisEvents(
        String companyId,
        LocalDate after,
        LocalDate through
    ) {
        return plannedEvents(companyId, after, through);
    }
}
