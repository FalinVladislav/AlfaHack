package org.alfahack.core.serialization;

import java.util.Map;

public interface JsonCodec {

    String write(
        Object value
    );

    Map<String, Object> read(
        String value
    );
}
