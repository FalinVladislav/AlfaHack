package org.alfahack.features.scenarios.port;

import java.util.Map;
import org.alfahack.core.domain.User;

public interface ScenarioDialogUseCases {

    Map<String, Object> prepare(
        User user,
        String draft,
        Map<String, Object> body
    );

    Map<String, Object> get(
        User user,
        String draft
    );

    Map<String, Object> send(
        User user,
        String draft,
        Map<String, Object> body
    );

    Map<String, Object> confirm(
        User user,
        String draft,
        Map<String, Object> body
    );

    Map<String, Object> retry(
        User user,
        String draft
    );

    Map<String, Object> calculate(
        User user,
        Map<String, Object> quote,
        int planVersion
    );

    void processNext();
}
