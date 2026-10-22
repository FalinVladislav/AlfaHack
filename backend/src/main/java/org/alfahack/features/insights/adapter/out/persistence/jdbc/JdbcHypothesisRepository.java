package org.alfahack.features.insights.adapter.out.persistence.jdbc;

import static org.alfahack.core.adapter.out.persistence.jdbc.JdbcRows.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.alfahack.core.errors.DomainException;
import org.alfahack.features.insights.port.HypothesisRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcHypothesisRepository implements HypothesisRepository {

    private final JdbcTemplate db;
    public JdbcHypothesisRepository(JdbcTemplate db) {
        this.db = db;
    }

    public Map<String, Object> ensure(
        String company,
        String fingerprint,
        String context
    ) {
        var jobs = db.queryForList("SELECT * FROM insight_jobs WHERE company_id=? AND fingerprint=?", company,
            fingerprint);
        if (jobs.isEmpty()) {
            try {
                var now = LocalDateTime.now();
                db.update(
                    "INSERT INTO insight_jobs (id,company_id,fingerprint,context_json,status,message,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), company, fingerprint, context, "QUEUED", "Готовим гипотезы", now,
                    now);
            } catch (DuplicateKeyException ignored) {
                /* Another request enqueued the same snapshot. */ }
            jobs = db.queryForList("SELECT * FROM insight_jobs WHERE company_id=? AND fingerprint=?", company,
                fingerprint);
        }
        return row(jobs.getFirst());
    }

    public void retry(
        String company,
        String jobId
    ) {
        db.update(
            "UPDATE insight_jobs SET status='QUEUED',message=?,updated_at=? WHERE id=? AND company_id=? AND status IN ('FAILED','PARTIAL')",
            "Повторяем обработку", LocalDateTime.now(), jobId, company);
    }

    public Map<String, Object> claim() {
        var now = LocalDateTime.now();
        var jobs = db.queryForList(
            "SELECT * FROM insight_jobs WHERE status='QUEUED' OR (status='RUNNING' AND updated_at<?) ORDER BY created_at LIMIT 1",
            now.minusMinutes(5));
        if (jobs.isEmpty())
            return null;
        var job = row(jobs.getFirst());
        String lease = UUID.randomUUID().toString();
        int claimed = db.update(
            "UPDATE insight_jobs SET status='RUNNING',lease_token=?,updated_at=? WHERE id=? AND (status='QUEUED' OR (status='RUNNING' AND updated_at<?))",
            lease, now, job.get("id"), now.minusMinutes(5));
        if (claimed == 0)
            return null;
        job.put("lease_token", lease);
        return job;
    }

    public boolean ownsLease(
        String jobId,
        String lease
    ) {
        return db.queryForObject("SELECT COUNT(*) FROM insight_jobs WHERE id=? AND status='RUNNING' AND lease_token=?",
            Integer.class, jobId, lease) == 1;
    }

    @Transactional
    public void publish(
        String jobId,
        String lease,
        String sourceKey,
        String payload
    ) {
        var jobs = db.queryForList(
            "SELECT * FROM insight_jobs WHERE id=? AND status='RUNNING' AND lease_token=? FOR UPDATE", jobId, lease);
        if (jobs.isEmpty())
            return;
        String id = UUID.nameUUIDFromBytes((jobId + ":" + sourceKey).getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString();
        if (db.queryForObject("SELECT COUNT(*) FROM business_hypotheses WHERE id=?", Integer.class, id) == 0) {
            db.update("INSERT INTO business_hypotheses VALUES (?,?,?,?,?,?)", id, jobs.getFirst().get("company_id"),
                jobId, sourceKey, payload, LocalDateTime.now());
        } else {
            db.update("UPDATE business_hypotheses SET payload_json=? WHERE id=?", payload, id);
        }
        db.update("UPDATE insight_jobs SET updated_at=? WHERE id=?", LocalDateTime.now(), jobId);
    }

    public void finish(
        String jobId,
        String lease,
        String status,
        String message
    ) {
        db.update(
            "UPDATE insight_jobs SET status=?,message=?,updated_at=? WHERE id=? AND lease_token=? AND status='RUNNING'",
            status, message, LocalDateTime.now(), jobId, lease);
    }

    public List<Map<String, Object>> cards(
        String company,
        String jobId
    ) {
        return rows(
            db.queryForList("SELECT * FROM business_hypotheses WHERE company_id=? AND job_id=? ORDER BY created_at,id",
                company, jobId));
    }

    public Map<String, Object> card(
        String company,
        String id
    ) {
        var cards = db.queryForList(
            "SELECT h.*,j.fingerprint FROM business_hypotheses h JOIN insight_jobs j ON j.id=h.job_id WHERE h.company_id=? AND h.id=?",
            company, id);
        if (cards.isEmpty())
            throw DomainException.missing();
        return row(cards.getFirst());
    }

    public List<Map<String, Object>> notifications(
        String company,
        String account
    ) {
        return rows(db.queryForList(
            "SELECT h.id,h.payload_json,h.created_at,CASE WHEN r.account_key IS NULL THEN FALSE ELSE TRUE END AS is_read FROM business_hypotheses h LEFT JOIN insight_reads r ON r.hypothesis_id=h.id AND r.account_key=? WHERE h.company_id=? ORDER BY h.created_at DESC,h.id LIMIT 50",
            account, company));
    }

    public void read(
        String company,
        String account,
        String id
    ) {
        card(company, id);
        try {
            db.update("INSERT INTO insight_reads VALUES (?,?,?)", account, id, LocalDateTime.now());
        } catch (DuplicateKeyException ignored) {
            /* Idempotent per-account acknowledgement. */ }
    }
}
