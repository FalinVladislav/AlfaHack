package org.alfahack.features.assistant.port;

import java.util.Map;

/** Outbound port: implementations may use HTTP, a queue, or a local process. */
public interface LanguageModel {

    boolean available();

    Map<String, Object> complete(
        String instructions,
        String message
    ) throws Exception;
}
