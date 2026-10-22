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
}
