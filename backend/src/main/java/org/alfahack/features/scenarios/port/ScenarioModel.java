package org.alfahack.features.scenarios.port;

import java.util.List;
import java.util.Map;

public interface ScenarioModel {

    Map<String, Object> complete(
        List<Map<String, Object>> messages,
        List<Map<String, Object>> tools
    ) throws Exception;
}
