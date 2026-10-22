package org.alfahack.features.assistant.port;

import java.util.Map;
import org.alfahack.core.domain.*;

public interface AssistantUseCases {

    Map<String, Object> respond(
        User user,
        Map<String, Object> body
    );

    Map<String, Object> apply(
        User user,
        String id,
        Map<String, Object> body
    );
}
