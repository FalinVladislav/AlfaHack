package org.alfahack.features.applications.adapter.out.persistence.jdbc;

import static org.alfahack.core.adapter.out.persistence.jdbc.JdbcRows.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.alfahack.core.domain.Util;
import org.alfahack.features.applications.port.ApplicationsRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcApplicationsRepository implements ApplicationsRepository {

    private final JdbcTemplate db;

    public JdbcApplicationsRepository(
        JdbcTemplate db
    ) {
        this.db = db;
    }

    @Override
    public List<Map<String, Object>> listOwned(
        String companyId
    ) {
        return rows(db.queryForList(
            "SELECT a.id,a.company_id,a.status,a.amount,a.product_id,a.created_at,a.assigned_to,a.score,a.term_months,a.duration_days,r.amount AS requested_amount,r.term_months AS requested_term_months,r.duration_days AS requested_duration_days FROM applications a LEFT JOIN application_requests r ON r.application_id=a.id WHERE a.company_id=? ORDER BY a.created_at DESC",
            companyId));
    }

    @Override
    public List<Map<String, Object>> listAll() {
        return rows(db.queryForList(
            "SELECT a.id,a.company_id,c.name AS company_name,a.status,a.amount,a.product_id,a.created_at,a.assigned_to,a.score,a.term_months,a.duration_days,r.amount AS requested_amount,r.term_months AS requested_term_months,r.duration_days AS requested_duration_days FROM applications a JOIN companies c ON c.id=a.company_id LEFT JOIN application_requests r ON r.application_id=a.id ORDER BY a.created_at DESC"));
    }

    @Override
    public List<Map<String, Object>> findOwned(
        String id,
        String companyId
    ) {
        return rows(db.queryForList("SELECT * FROM applications WHERE id=? AND company_id=?", id, companyId));
    }

    @Override
    public List<Map<String, Object>> findById(
        String id
    ) {
        return rows(db.queryForList("SELECT * FROM applications WHERE id=?", id));
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
            "SELECT actor,old_status,new_status,reason_code,event_type,details,created_at FROM application_events WHERE application_id=? ORDER BY created_at,id",
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
        return rows(db.queryForList("SELECT report_json,assessed_at FROM scoring_reports WHERE application_id=?",
            applicationId));
    }

    @Override
    public List<Map<String, Object>> scoringRuns(String applicationId) {
        return rows(db.queryForList(
            "SELECT report_json,assessed_at FROM scoring_runs WHERE application_id=? ORDER BY assessed_at,id",
            applicationId));
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
        return db.update(
            "INSERT INTO applications(id,company_id,draft_id,quote_id,status,demo_profile,amount,term_months,duration_days,product_id,annual_rate,monthly_payment,total_repay,score,reason_code,offer_version,version,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id, companyId,
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
        LocalDateTime assessedAt = LocalDateTime.now();
        int result = db.update("INSERT INTO scoring_reports(application_id,report_json,assessed_at) VALUES (?,?,?)",
            applicationId, reportJson, assessedAt);
        db.update("INSERT INTO scoring_runs(id,application_id,report_json,assessed_at) VALUES (?,?,?,?)",
            Util.id(), applicationId, reportJson, assessedAt);
        return result;
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
        return row(db.queryForMap("SELECT needed_by FROM drafts WHERE id=?", draftId));
    }

    @Override
    public Map<String, Object> companyBalance(
        String companyId
    ) {
        return row(db.queryForMap("SELECT balance FROM companies WHERE id=?", companyId));
    }

    @Override
    public Map<String, Object> plannedBalance(
        String companyId,
        LocalDate after,
        LocalDate through
    ) {
        return row(db.queryForMap(
            "SELECT COALESCE(SUM(amount),0) AS total FROM ledger_events WHERE company_id=? AND event_date>? AND event_date<=? AND kind<>'DISBURSEMENT'",
            companyId, after, through));
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
        return db.update(
            "INSERT INTO loans(id,application_id,company_id,amount,balance,next_payment_date,next_payment_amount,created_at) VALUES (?,?,?,?,?,?,?,?)",
            id, applicationId, companyId, principal,
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
        return db.update(
            "INSERT INTO application_events(id,application_id,actor,old_status,new_status,reason_code,created_at) VALUES (?,?,?,?,?,?,?)",
            id, applicationId, actor, oldStatus,
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

    @Override
    public int insertSnapshot(
        String applicationId,
        Map<String, Object> draft,
        Map<String, Object> quote,
        LocalDateTime submittedAt
    ) {
        return db.update(
            "INSERT INTO application_requests(application_id,purpose,goal_text,comment,product_id,amount,term_months,duration_days,needed_by,annual_rate,monthly_payment,total_repay,submitted_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            applicationId, draft.get("purpose"), draft.get("goal_text"), null, quote.get("product_id"),
            quote.get("amount"), quote.get("term_months"), quote.get("duration_days"), draft.get("needed_by"),
            quote.get("annual_rate"), quote.get("monthly_payment"), quote.get("total_repay"), submittedAt);
    }

    @Override
    public Map<String, Object> snapshot(String applicationId) {
        return row(db.queryForMap("SELECT * FROM application_requests WHERE application_id=?", applicationId));
    }

    @Override
    public Map<String, Object> company(String companyId) {
        return row(db.queryForMap(
            "SELECT c.*,f.current_assets,f.current_liabilities,f.collateral_value,f.overdue_days,f.credit_history,f.source AS risk_source,f.updated_at AS risk_updated_at FROM companies c LEFT JOIN company_risk_facts f ON f.company_id=c.id WHERE c.id=?",
            companyId));
    }

    @Override
    public List<Map<String, Object>> transactions(String companyId) {
        return rows(
            db.queryForList("SELECT date,amount,type FROM transactions WHERE company_id=? ORDER BY date", companyId));
    }

    @Override
    public List<Map<String, Object>> existingLoans(String companyId) {
        return rows(db.queryForList(
            "SELECT id,name,remaining_debt,monthly_payment,rate,next_payment_date,end_date FROM existing_loans WHERE company_id=? ORDER BY id",
            companyId));
    }

    @Override
    public List<Map<String, Object>> offers(String applicationId) {
        return rows(db.queryForList("SELECT * FROM application_offers WHERE application_id=? ORDER BY version DESC",
            applicationId));
    }

    @Override
    public List<Map<String, Object>> decisions(String applicationId) {
        return rows(db.queryForList("SELECT * FROM bank_decisions WHERE application_id=?", applicationId));
    }

    @Override
    public List<Map<String, Object>> responses(String applicationId) {
        return rows(db.queryForList("SELECT * FROM application_responses WHERE application_id=?", applicationId));
    }

    @Override
    public List<Map<String, Object>> contracts(String applicationId) {
        return rows(db.queryForList("SELECT * FROM application_contracts WHERE application_id=?", applicationId));
    }

    @Override
    public List<Map<String, Object>> schedule(String loanId) {
        return rows(db.queryForList(
            "SELECT installment,due_date,principal,interest,payment FROM loan_schedule WHERE loan_id=? ORDER BY installment",
            loanId));
    }

    @Override
    public List<Map<String, Object>> notifications(String companyId) {
        return rows(db.queryForList(
            "SELECT id,application_id,message,created_at,read_at FROM application_notifications WHERE company_id=? ORDER BY created_at DESC",
            companyId));
    }

    @Override
    public int claim(
        String applicationId,
        String staffKey,
        int expectedVersion,
        LocalDateTime now
    ) {
        return db.update(
            "UPDATE applications SET assigned_to=?,status='IN_REVIEW',version=version+1,updated_at=? WHERE id=? AND status='AWAITING_DECISION' AND assigned_to IS NULL AND version=?",
            staffKey, now, applicationId, expectedVersion);
    }

    @Override
    public int move(
        String applicationId,
        String oldStatus,
        String newStatus,
        int expectedVersion,
        String staffKey,
        String reasonCode,
        LocalDateTime now
    ) {
        String sql = "UPDATE applications SET status=?,reason_code=?,version=version+1,updated_at=? WHERE id=? AND status=? AND version=?";
        if (staffKey != null)
            return db.update(sql + " AND assigned_to=?", newStatus, reasonCode, now, applicationId, oldStatus,
                expectedVersion, staffKey);
        return db.update(sql, newStatus, reasonCode, now, applicationId, oldStatus, expectedVersion);
    }

    @Override
    public int score(
        String applicationId,
        int score,
        LocalDateTime assessedAt
    ) {
        db.update("UPDATE scoring_reports SET assessed_at=? WHERE application_id=?", assessedAt, applicationId);
        return db.update("UPDATE applications SET score=? WHERE id=?", score, applicationId);
    }

    @Override
    public int insertOffer(
        String id,
        String applicationId,
        int version,
        String productId,
        BigDecimal amount,
        Integer months,
        Integer days,
        BigDecimal rate,
        BigDecimal payment,
        BigDecimal total,
        LocalDateTime expiresAt,
        LocalDateTime createdAt
    ) {
        return db.update("INSERT INTO application_offers VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", id, applicationId,
            version, productId, amount, months, days, rate, payment, total, expiresAt, createdAt);
    }

    @Override
    public int insertDecision(
        String applicationId,
        int version,
        String staffKey,
        String type,
        String offerId,
        String scoringSnapshot,
        String internalReason,
        String clientMessage,
        String divergenceReason,
        String reasonCode,
        LocalDateTime decidedAt
    ) {
        return db.update(
            "INSERT INTO bank_decisions(application_id,application_version,staff_key,decision_type,offer_id,scoring_snapshot,internal_reason,client_message,divergence_reason,reason_code,decided_at,reason) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            applicationId, version, staffKey, type, offerId, scoringSnapshot, internalReason, clientMessage,
            divergenceReason, null, decidedAt, reasonCode);
    }

    @Override
    public int insertResponse(
        String applicationId,
        String offerId,
        int offerVersion,
        String response,
        LocalDateTime respondedAt
    ) {
        return db.update("INSERT INTO application_responses VALUES (?,?,?,?,?)", applicationId, offerId,
            offerVersion, response, respondedAt);
    }

    @Override
    public int insertContract(
        String applicationId,
        String offerId,
        int offerVersion,
        String number,
        LocalDateTime createdAt
    ) {
        return db.update(
            "INSERT INTO application_contracts(application_id,offer_id,offer_version,number,created_at) VALUES (?,?,?,?,?)",
            applicationId, offerId, offerVersion, number, createdAt);
    }

    @Override
    public int signContract(
        String applicationId,
        LocalDateTime signedAt
    ) {
        return db.update("UPDATE application_contracts SET signed_at=? WHERE application_id=? AND signed_at IS NULL",
            signedAt, applicationId);
    }

    @Override
    public int insertNotification(
        String id,
        String applicationId,
        String companyId,
        String message,
        LocalDateTime createdAt
    ) {
        return db.update("INSERT INTO application_notifications VALUES (?,?,?,?,?,NULL)", id, applicationId,
            companyId, message, createdAt);
    }

    @Override
    public int readNotification(
        String id,
        String companyId,
        LocalDateTime readAt
    ) {
        return db.update(
            "UPDATE application_notifications SET read_at=? WHERE id=? AND company_id=? AND read_at IS NULL",
            readAt, id, companyId);
    }

    @Override
    public int insertSchedule(
        String loanId,
        int installment,
        LocalDate dueDate,
        BigDecimal principal,
        BigDecimal interest,
        BigDecimal payment
    ) {
        return db.update("INSERT INTO loan_schedule VALUES (?,?,?,?,?,?)", loanId, installment, dueDate, principal,
            interest, payment);
    }

    @Override
    public int insertAuditEvent(
        String id,
        String applicationId,
        String actor,
        String oldStatus,
        String newStatus,
        String eventType,
        String details,
        LocalDateTime createdAt
    ) {
        return db.update(
            "INSERT INTO application_events(id,application_id,actor,old_status,new_status,event_type,details,created_at) VALUES (?,?,?,?,?,?,?,?)",
            id, applicationId, actor, oldStatus, newStatus, eventType, details, createdAt);
    }
}
