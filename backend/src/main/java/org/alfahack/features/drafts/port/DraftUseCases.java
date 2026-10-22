package org.alfahack.features.drafts.port;

import java.util.List;
import java.util.Map;
import org.alfahack.core.domain.*;

public interface DraftUseCases {

    Map<String, Object> get(
        User user,
        String id
    );

    List<Map<String, Object>> list(
        User user
    );

    Map<String, Object> create(
        User user
    );

    Map<String, Object> update(
        User user,
        String id,
        Map<String, Object> body
    );

    Map<String, Object> quote(
        User user,
        String id,
        Map<String, Object> body
    );

    Map<String, Object> quoteById(
        User user,
        String id
    );
}
