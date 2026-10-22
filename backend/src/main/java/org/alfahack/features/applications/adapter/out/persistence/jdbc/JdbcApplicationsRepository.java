package org.alfahack.features.applications.adapter.out.persistence.jdbc;

import static org.alfahack.core.adapter.out.persistence.jdbc.JdbcRows.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.alfahack.features.applications.port.ApplicationsRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcApplicationsRepository implements ApplicationsRepository {

    private final JdbcTemplate db;
    private final org.alfahack.features.forecast.port.ForecastRepository forecast;

    public JdbcApplicationsRepository(
        JdbcTemplate db,
        org.alfahack.features.forecast.port.ForecastRepository forecast
    ) {
        this.db = db;
        this.forecast = forecast;
    }

    @Override
    public List<Map<String, Object>> listOwned(
        String companyId
    ) {
        return rows(db.queryForList(
            "SELECT id,company_id,status,amount,product_id,created_at FROM applications WHERE company_id=? ORDER BY created_at DESC",
            companyId));
    }

    @Override
    public List<Map<String, Object>> listAll() {
        return rows(db.queryForList(
            "SELECT id,company_id,status,amount,product_id,created_at FROM applications ORDER BY created_at DESC"));
    }

    @Override
    public List<Map<String, Object>> findOwned(
        String id,
        String companyId
    ) {
        return rows(
            db.queryForList("SELECT * FROM applications WHERE id=? AND company_id=? FOR UPDATE", id, companyId));
    }

    @Override
    public List<Map<String, Object>> findById(
        String id
    ) {
        return rows(db.queryForList("SELECT * FROM applications WHERE id=? FOR UPDATE", id));
    }

    @Override
    public List<Map<String, Object>> requests(
        String applicationId
    ) {
        return rows(db.queryForList("SELECT * FROM info_requests WHERE application_id=?", applicationId));
    }

    @Override
    public List<Map<String, Object>> events(
        String applicationId
    ) {
        return rows(db.queryForList(
            "SELECT actor,old_status,new_status,reason_code,created_at FROM application_events WHERE application_id=? ORDER BY created_at",
            applicationId));
    }

    @Override
    public List<Map<String, Object>> applicationLoans(
        String applicationId
    ) {
        return rows(db.queryForList("SELECT * FROM loans WHERE application_id=?", applicationId));
    }

    @Override
    public List<Map<String, Object>> reports(
        String applicationId
    ) {
        return rows(db.queryForList("SELECT report_json FROM scoring_reports WHERE application_id=?", applicationId));
    }

    @Override
    public int submitDraft(
        LocalDateTime updatedAt,
        String draftId,
        String companyId,
        Integer expectedVersion
    ) {
        return db.update(
            "UPDATE drafts SET status='SUBMITTED',updated_at=? WHERE id=? AND company_id=? AND version=? AND status='ACTIVE'",
            updatedAt, draftId, companyId, expectedVersion);
    }

    @Override
    public int insert(
        String id,
        String companyId,
        String draftId,
        String quoteId,
        String status,
        String demoProfile,
        BigDecimal amount,
        Integer termMonths,
        Integer durationDays,
        String productId,
        BigDecimal annualRate,
        BigDecimal monthlyPayment,
        BigDecimal totalRepay,
        Integer score,
        String reasonCode,
        Integer offerVersion,
        Integer version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
    ) {
        return db.update("INSERT INTO applications VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", id, companyId,
            draftId, quoteId, status, demoProfile, amount, termMonths, durationDays, productId, annualRate,
            monthlyPayment, totalRepay, score, reasonCode, offerVersion, version, createdAt, updatedAt);
    }

    @Override
    public int lockProfile(
        String profile,
        String id
    ) {
        return db.update("UPDATE applications SET demo_profile=? WHERE id=? AND demo_profile IS NULL", profile, id);
    }

    @Override
    public Integer pendingRequests(
        String applicationId
    ) {
        return db.queryForObject("SELECT COUNT(*) FROM info_requests WHERE application_id=? AND status<>'VERIFIED'",
            Integer.class, applicationId);
    }

    @Override
    public Integer verifiedRequests(
        String applicationId
    ) {
        return db.queryForObject("SELECT COUNT(*) FROM info_requests WHERE application_id=? AND status='VERIFIED'",
            Integer.class, applicationId);
    }

    @Override
    public int insertRequest(
        String id,
        String applicationId,
        String reasonCode,
        String documentType,
        String period,
        String status
    ) {
        return db.update("INSERT INTO info_requests VALUES (?,?,?,?,?,?)", id, applicationId, reasonCode, documentType,
            period, status);
    }

    @Override
    public int deleteReport(
        String applicationId
    ) {
        return db.update("DELETE FROM scoring_reports WHERE application_id=?", applicationId);
    }

    @Override
    public int insertReport(
        String applicationId,
        String reportJson
    ) {
        return db.update("INSERT INTO scoring_reports VALUES (?,?)", applicationId, reportJson);
    }

    @Override
    public int insertMissingIncomeRequest(
        String id,
        String applicationId,
        String reasonCode,
        String documentType,
        String period,
        String status
    ) {
        return db.update("INSERT INTO info_requests VALUES (?,?,?,?,?,?)", id, applicationId, reasonCode, documentType,
            period, status);
    }

    @Override
    public int updateScore(
        Integer score,
        String id
    ) {
        return db.update("UPDATE applications SET score=? WHERE id=?", score, id);
    }

    @Override
    public Map<String, Object> draftDate(
        String draftId
    ) {
        return row(db.queryForMap("SELECT needed_by,purpose FROM drafts WHERE id=?", draftId));
    }

    @Override
    public Map<String, Object> companyBalance(
        String companyId
    ) {
        return row(db.queryForMap(
            "SELECT balance, (SELECT COALESCE(SUM(monthly_payment),0) FROM existing_loans WHERE company_id=c.id) + (SELECT COALESCE(SUM(next_payment_amount),0) FROM loans WHERE company_id=c.id) AS monthly_debt FROM companies c WHERE id=?",
            companyId));
    }

    @Override
    public Map<String, Object> plannedBalance(
        String companyId,
        LocalDate after,
        LocalDate through
    ) {
        BigDecimal total = forecast.plannedEvents(companyId, after, through).stream()
            .map(e -> (BigDecimal) e.get("amount")).reduce(BigDecimal.ZERO, BigDecimal::add);
        return Map.of("total", total);
    }

    @Override
    public int reduceOverdraft(
        BigDecimal amount,
        BigDecimal totalRepay,
        String id
    ) {
        return db.update("UPDATE applications SET amount=?,total_repay=?,offer_version=offer_version+1 WHERE id=?",
            amount, totalRepay, id);
    }

    @Override
    public int reduceLoan(
        BigDecimal amount,
        BigDecimal monthlyPayment,
        BigDecimal totalRepay,
        String id
    ) {
        return db.update(
            "UPDATE applications SET amount=?,monthly_payment=?,total_repay=?,offer_version=offer_version+1 WHERE id=?",
            amount, monthlyPayment, totalRepay, id);
    }

    @Override
    public int insertLoan(
        String id,
        String applicationId,
        String companyId,
        BigDecimal principal,
        BigDecimal outstanding,
        LocalDate nextPaymentDate,
        BigDecimal nextPayment,
        LocalDateTime createdAt
    ) {
        return db.update("INSERT INTO loans VALUES (?,?,?,?,?,?,?,?)", id, applicationId, companyId, principal,
            outstanding, nextPaymentDate, nextPayment, createdAt);
    }

    @Override
    public int insertLedgerEvent(
        String id,
        String companyId,
        LocalDate eventDate,
        BigDecimal amount,
        String kind,
        String source
    ) {
        return db.update("INSERT INTO ledger_events VALUES (?,?,?,?,?,?)", id, companyId, eventDate, amount, kind,
            source);
    }

    @Override
    public int creditCompany(
        BigDecimal amount,
        String companyId
    ) {
        return db.update("UPDATE companies SET balance=balance+? WHERE id=?", amount, companyId);
    }

    @Override
    public List<Map<String, Object>> loans(
        String companyId
    ) {
        return rows(db.queryForList("SELECT * FROM loans WHERE company_id=? ORDER BY created_at DESC", companyId));
    }

    @Override
    public List<Map<String, Object>> loan(
        String id,
        String companyId
    ) {
        return rows(db.queryForList("SELECT * FROM loans WHERE id=? AND company_id=?", id, companyId));
    }

    @Override
    public int insertEvent(
        String id,
        String applicationId,
        String actor,
        String oldStatus,
        String newStatus,
        String reasonCode,
        LocalDateTime createdAt
    ) {
        return db.update("INSERT INTO application_events VALUES (?,?,?,?,?,?,?)", id, applicationId, actor, oldStatus,
            newStatus, reasonCode, createdAt);
    }

    @Override
    public int transition(
        String newStatus,
        String reasonCode,
        LocalDateTime updatedAt,
        String id,
        String oldStatus
    ) {
        return db.update(
            "UPDATE applications SET status=?,reason_code=?,version=version+1,updated_at=? WHERE id=? AND status=?",
            newStatus, reasonCode, updatedAt, id, oldStatus);
    }

    @Override
    public List<Map<String, Object>> idempotencyRecord(
        String accountKey,
        String operation,
        String requestKey
    ) {
        return rows(db.queryForList(
            "SELECT body_hash,result_id FROM idempotency_records WHERE account_key=? AND operation=? AND request_key=?",
            accountKey, operation, requestKey));
    }

    @Override
    public int storeIdempotency(
        String accountKey,
        String operation,
        String requestKey,
        String bodyHash,
        String resultId
    ) {
        return db.update("INSERT INTO idempotency_records VALUES (?,?,?,?,?)", accountKey, operation, requestKey,
            bodyHash, resultId);
    }
}
