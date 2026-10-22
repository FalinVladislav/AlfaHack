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
        return rows(db.queryForList(
            "SELECT event_date,amount,kind,source FROM ledger_events WHERE company_id=? AND event_date>? AND event_date<=? AND kind<>'DISBURSEMENT' ORDER BY event_date",
            companyId, after, through));
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
        return rows(db.queryForList(
            "SELECT event_date,amount FROM ledger_events WHERE company_id=? AND event_date>? AND event_date<=? AND kind<>'DISBURSEMENT'",
            companyId, after, through));
    }
}
