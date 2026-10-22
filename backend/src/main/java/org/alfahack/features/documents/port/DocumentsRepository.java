package org.alfahack.features.documents.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface DocumentsRepository {

    List<Map<String, Object>> request(
        String id,
        String applicationId
    );

    int insert(
        String id,
        String requestId,
        String companyId,
        String filename,
        String mime,
        String sha256,
        String storage,
        Boolean verified,
        LocalDateTime uploadedAt
    );

    int verify(
        String requestId
    );

    List<Map<String, Object>> download(
        String id,
        String applicationId
    );

    List<Map<String, Object>> requestStatus(
        String id,
        String applicationId
    );

    List<Map<String, Object>> metadata(
        String id,
        String applicationId
    );
}
