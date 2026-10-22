package org.alfahack.features.insights.port;

import java.util.List;
import java.util.Map;

public interface HypothesisRepository {

    Map<String, Object> ensure(
        String company,
        String fingerprint,
        String context
    );

    void retry(
        String company,
        String jobId
    );

    Map<String, Object> claim();

    boolean ownsLease(
        String jobId,
        String lease
    );

    void publish(
        String jobId,
        String lease,
        String sourceKey,
        String payload
    );

    void finish(
        String jobId,
        String lease,
        String status,
        String message
    );

    List<Map<String, Object>> cards(
        String company,
        String jobId
    );

    Map<String, Object> card(
        String company,
        String id
    );

    List<Map<String, Object>> notifications(
        String company,
        String account
    );

    void read(
        String company,
        String account,
        String id
    );
}
