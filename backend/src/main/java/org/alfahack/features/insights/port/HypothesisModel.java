package org.alfahack.features.insights.port;

import java.util.Map;

public interface HypothesisModel {

    boolean available();

    Map<String, Object> explain(Map<String, Object> context) throws Exception;
}
