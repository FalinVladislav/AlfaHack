package org.alfahack.features.applications.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface ApplicationsRepository {

    List<Map<String, Object>> listOwned(
        String companyId
    );

    List<Map<String, Object>> listAll();

    Map<String, Object> pageStaff(
        String staffKey,
        String tab,
        String query,
        String status,
        String risk,
        String assignee,
        int page,
        int size
    );

    int scoringSummary(
        String id,
        String outcome,
        String risk
    );

    List<String> expiringOffers();

    List<Map<String, Object>> findOwned(
        String id,
        String companyId
    );

    List<Map<String, Object>> findById(
        String id
    );

    List<Map<String, Object>> requests(
        String applicationId
    );

    List<Map<String, Object>> events(
        String applicationId
    );

    List<Map<String, Object>> applicationLoans(
        String applicationId
    );

    List<Map<String, Object>> reports(
        String applicationId
    );

    List<Map<String, Object>> scoringRuns(String applicationId);

    int submitDraft(
        LocalDateTime updatedAt,
        String draftId,
        String companyId,
        Integer expectedVersion
    );

    int insert(
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
    );

    int lockProfile(
        String profile,
        String id
    );

    Integer pendingRequests(
        String applicationId
    );

    Integer verifiedRequests(
        String applicationId
    );

    int insertRequest(
        String id,
        String applicationId,
        String reasonCode,
        String documentType,
        String period,
        String status
    );

    int deleteReport(
        String applicationId
    );

    int insertReport(
        String applicationId,
        String reportJson
    );

    int insertMissingIncomeRequest(
        String id,
        String applicationId,
        String reasonCode,
        String documentType,
        String period,
        String status
    );

    int updateScore(
        Integer score,
        String id
    );

    Map<String, Object> draftDate(
        String draftId
    );

    Map<String, Object> companyBalance(
        String companyId
    );

    Map<String, Object> plannedBalance(
        String companyId,
        LocalDate after,
        LocalDate through
    );

    int reduceOverdraft(
        BigDecimal amount,
        BigDecimal totalRepay,
        String id
    );

    int reduceLoan(
        BigDecimal amount,
        BigDecimal monthlyPayment,
        BigDecimal totalRepay,
        String id
    );

    int insertLoan(
        String id,
        String applicationId,
        String companyId,
        BigDecimal principal,
        BigDecimal outstanding,
        LocalDate nextPaymentDate,
        BigDecimal nextPayment,
        LocalDateTime createdAt
    );

    int insertLedgerEvent(
        String id,
        String companyId,
        LocalDate eventDate,
        BigDecimal amount,
        String kind,
        String source
    );

    int creditCompany(
        BigDecimal amount,
        String companyId
    );

    void recordDisbursement(
        String eventId,
        String companyId,
        LocalDate date,
        BigDecimal amount
    );

    List<Map<String, Object>> loans(
        String companyId
    );

    List<Map<String, Object>> loan(
        String id,
        String companyId
    );

    int insertEvent(
        String id,
        String applicationId,
        String actor,
        String oldStatus,
        String newStatus,
        String reasonCode,
        LocalDateTime createdAt
    );

    int transition(
        String newStatus,
        String reasonCode,
        LocalDateTime updatedAt,
        String id,
        String oldStatus
    );

    List<Map<String, Object>> idempotencyRecord(
        String accountKey,
        String operation,
        String requestKey
    );

    int storeIdempotency(
        String accountKey,
        String operation,
        String requestKey,
        String bodyHash,
        String resultId
    );

    int insertSnapshot(
        String applicationId,
        Map<String, Object> draft,
        Map<String, Object> quote,
        LocalDateTime submittedAt
    );

    Map<String, Object> snapshot(String applicationId);

    Map<String, Object> company(String companyId);

    List<Map<String, Object>> transactions(String companyId);

    List<Map<String, Object>> existingLoans(String companyId);

    List<Map<String, Object>> offers(String applicationId);

    List<Map<String, Object>> decisions(String applicationId);

    List<Map<String, Object>> responses(String applicationId);

    List<Map<String, Object>> contracts(String applicationId);

    List<Map<String, Object>> schedule(String loanId);

    List<Map<String, Object>> notifications(String companyId);

    int claim(
        String applicationId,
        String staffKey,
        int expectedVersion,
        LocalDateTime now
    );

    int move(
        String applicationId,
        String oldStatus,
        String newStatus,
        int expectedVersion,
        String staffKey,
        String reasonCode,
        LocalDateTime now
    );

    int score(
        String applicationId,
        int score,
        LocalDateTime assessedAt
    );

    int insertOffer(
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
    );

    int insertDecision(
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
    );

    int insertResponse(
        String applicationId,
        String offerId,
        int offerVersion,
        String response,
        LocalDateTime respondedAt
    );

    int insertContract(
        String applicationId,
        String offerId,
        int offerVersion,
        String number,
        LocalDateTime createdAt
    );

    int signContract(
        String applicationId,
        LocalDateTime signedAt
    );

    int insertNotification(
        String id,
        String applicationId,
        String companyId,
        String message,
        LocalDateTime createdAt
    );

    int readNotification(
        String id,
        String companyId,
        LocalDateTime readAt
    );

    int insertSchedule(
        String loanId,
        int installment,
        LocalDate dueDate,
        BigDecimal principal,
        BigDecimal interest,
        BigDecimal payment
    );

    int insertAuditEvent(
        String id,
        String applicationId,
        String actor,
        String oldStatus,
        String newStatus,
        String eventType,
        String details,
        LocalDateTime createdAt
    );
}
