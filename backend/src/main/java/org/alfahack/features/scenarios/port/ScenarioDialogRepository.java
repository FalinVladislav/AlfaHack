package org.alfahack.features.scenarios.port;

import java.util.Map;

public interface ScenarioDialogRepository {

    Map<String, Object> find(
        String draft,
        String account,
        String goalKey
    );

    Map<String, Object> ensure(
        String draft,
        String account,
        String company,
        String goalKey
    );

    Map<String, Object> lock(String id);

    void save(Map<String, Object> dialog);

    Map<String, Object> claim();

    void recordRun(
        String dialog,
        String quote,
        int version,
        String context,
        String result
    );
}
