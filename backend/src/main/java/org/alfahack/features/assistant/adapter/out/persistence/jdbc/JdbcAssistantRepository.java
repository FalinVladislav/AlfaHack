package org.alfahack.features.assistant.adapter.out.persistence.jdbc;

import static org.alfahack.core.adapter.out.persistence.jdbc.JdbcRows.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.alfahack.features.assistant.port.AssistantRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAssistantRepository implements AssistantRepository {

    private final JdbcTemplate db;

    public JdbcAssistantRepository(
        JdbcTemplate db
    ) {
        this.db = db;
    }

    @Override
    public int insertProposal(
        String id,
        String companyId,
        String draftId,
        Integer draftVersion,
        BigDecimal amount,
        Integer termMonths,
        Integer durationDays,
        String purpose,
        LocalDate neededBy,
        LocalDateTime createdAt
    ) {
        return db.update("INSERT INTO assistant_proposals VALUES (?,?,?,?,?,?,?,?,?,?)", id, companyId, draftId,
            draftVersion, amount, termMonths, durationDays, purpose, neededBy, createdAt);
    }

    @Override
    public List<Map<String, Object>> proposal(
        String id,
        String companyId
    ) {
        return rows(db.queryForList("SELECT * FROM assistant_proposals WHERE id=? AND company_id=?", id, companyId));
    }

    @Override
    public int deleteProposal(
        String id
    ) {
        return db.update("DELETE FROM assistant_proposals WHERE id=?", id);
    }
}
