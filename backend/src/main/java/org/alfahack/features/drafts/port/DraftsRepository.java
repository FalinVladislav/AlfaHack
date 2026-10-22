package org.alfahack.features.drafts.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface DraftsRepository {

    List<Map<String, Object>> findOwned(
        String id,
        String companyId
    );

    List<Map<String, Object>> listOwned(
        String companyId
    );

    int insert(
        String id,
        String companyId,
        LocalDateTime updatedAt
    );

    int updateVersion(
        String purpose,
        BigDecimal amount,
        Integer termMonths,
        Integer durationDays,
        LocalDate neededBy,
        String productId,
        LocalDateTime updatedAt,
        String id,
        String companyId,
        Integer expectedVersion
    );

    int insertQuote(
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
    );

    List<Map<String, Object>> findOwnedQuote(
        String id,
        String companyId
    );
}
