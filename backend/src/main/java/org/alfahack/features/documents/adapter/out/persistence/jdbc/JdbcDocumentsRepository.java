package org.alfahack.features.documents.adapter.out.persistence.jdbc;

import static org.alfahack.core.adapter.out.persistence.jdbc.JdbcRows.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.alfahack.features.documents.port.DocumentsRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDocumentsRepository implements DocumentsRepository {

    private final JdbcTemplate db;

    public JdbcDocumentsRepository(
        JdbcTemplate db
    ) {
        this.db = db;
    }

    @Override
    public List<Map<String, Object>> request(
        String id,
        String applicationId
    ) {
        return rows(db.queryForList("SELECT * FROM info_requests WHERE id=? AND application_id=?", id, applicationId));
    }

    @Override
    public int insert(
        String id,
        String requestId,
        String companyId,
        String filename,
        String mime,
        String sha256,
        String storage,
        Boolean verified,
        LocalDateTime uploadedAt
    ) {
        return db.update("INSERT INTO documents VALUES (?,?,?,?,?,?,?,?,?)", id, requestId, companyId, filename, mime,
            sha256, storage, verified, uploadedAt);
    }

    @Override
    public int verify(
        String requestId
    ) {
        return db.update("UPDATE info_requests SET status='VERIFIED' WHERE id=?", requestId);
    }

    @Override
    public List<Map<String, Object>> download(
        String id,
        String applicationId
    ) {
        return rows(db.queryForList(
            "SELECT d.* FROM documents d JOIN info_requests r ON r.id=d.request_id WHERE d.id=? AND r.application_id=?",
            id, applicationId));
    }

    @Override
    public List<Map<String, Object>> requestStatus(
        String id,
        String applicationId
    ) {
        return rows(
            db.queryForList("SELECT status FROM info_requests WHERE id=? AND application_id=?", id, applicationId));
    }

    @Override
    public List<Map<String, Object>> metadata(
        String id,
        String applicationId
    ) {
        return rows(db.queryForList(
            "SELECT d.id,d.filename,d.mime,d.sha256,d.verified,d.uploaded_at FROM documents d JOIN info_requests r ON r.id=d.request_id WHERE d.id=? AND r.application_id=?",
            id, applicationId));
    }
}
