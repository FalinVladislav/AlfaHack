package org.alfahack.features.forecast.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface ForecastRepository {

    Map<String, Object> company(
        String companyId
    );

    Map<String, Object> balance(
        String companyId
    );

    List<Map<String, Object>> plannedEvents(
        String companyId,
        LocalDate after,
        LocalDate through
    );

    Map<String, Object> draft(
        String draftId
    );

    Map<String, Object> analysisBalance(
        String companyId
    );

    List<Map<String, Object>> analysisEvents(
        String companyId,
        LocalDate after,
        LocalDate through
    );
}
