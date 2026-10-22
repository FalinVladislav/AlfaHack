package org.alfahack.features.insights.port;

import java.util.Map;
import java.util.List;
import org.alfahack.core.domain.User;

public interface HypothesisUseCases {

    Map<String, Object> list(User user);

    Map<String, Object> detail(
        User user,
        String id
    );

    Map<String, Object> retry(User user);

    List<Map<String, Object>> notifications(User user);

    void read(
        User user,
        String id
    );

    void processNext();
}
