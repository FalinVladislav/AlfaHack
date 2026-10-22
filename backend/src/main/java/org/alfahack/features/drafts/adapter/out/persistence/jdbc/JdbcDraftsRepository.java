package org.alfahack.features.drafts.adapter.out.persistence.jdbc;

import static org.alfahack.core.adapter.out.persistence.jdbc.JdbcRows.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.alfahack.features.drafts.port.DraftsRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDraftsRepository implements DraftsRepository {

    private final JdbcTemplate db;

    public JdbcDraftsRepository(
        JdbcTemplate db
    ) {
        this.db = db;
    }

    @Override
    public List<Map<String, Object>> findOwned(
        String id,
        String companyId
    ) {
        return rows(db.queryForList("SELECT * FROM drafts WHERE id=? AND company_id=?", id, companyId));
    }

    @Override
    public List<Map<String, Object>> listOwned(
        String companyId
    ) {
        return rows(db.queryForList("SELECT * FROM drafts WHERE company_id=? ORDER BY updated_at DESC", companyId));
    }

    @Override
    public int insert(
        String id,
        String companyId,
        LocalDateTime updatedAt
    ) {
        return db.update("INSERT INTO drafts(id,company_id,version,status,updated_at) VALUES (?,?,1,'ACTIVE',?)", id,
            companyId, updatedAt);
    }

    @Override
    public int updateVersion(
        String purpose,
        BigDecimal amount,
        Integer termMonths,
        Integer durationDays,
        LocalDate neededBy,
        String productId,
        String goalId,
        String goalText,
        LocalDateTime updatedAt,
        String id,
        String companyId,
        Integer expectedVersion
    ) {
        return db.update(
            "UPDATE drafts SET version=version+1,purpose=?,amount=?,term_months=?,duration_days=?,needed_by=?,product_id=?,goal_id=?,goal_text=?,updated_at=? WHERE id=? AND company_id=? AND version=? AND status='ACTIVE'",
            purpose, amount, termMonths, durationDays, neededBy, productId, goalId, goalText, updatedAt, id, companyId,
            expectedVersion);
    }

    @Override
    public int insertQuote(
        String id,
        String draftId,
        Integer draftVersion,
        String productId,
        BigDecimal amount,
        Integer termMonths,
        Integer durationDays,
        BigDecimal annualRate,
        BigDecimal monthlyPayment,
        BigDecimal totalRepay,
        LocalDateTime createdAt
    ) {
        return db.update("INSERT INTO quotes VALUES (?,?,?,?,?,?,?,?,?,?,?)", id, draftId, draftVersion, productId,
            amount, termMonths, durationDays, annualRate, monthlyPayment, totalRepay, createdAt);
    }

    @Override
    public List<Map<String, Object>> findOwnedQuote(
        String id,
        String companyId
    ) {
        return rows(
            db.queryForList("SELECT q.* FROM quotes q JOIN drafts d ON d.id=q.draft_id WHERE q.id=? AND d.company_id=?",
                id, companyId));
    }
}
