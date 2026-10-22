package org.alfahack.features.assistant.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface AssistantRepository {

    int insertProposal(
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
    );

    List<Map<String, Object>> proposal(
        String id,
        String companyId
    );

    int deleteProposal(
        String id
    );
}
