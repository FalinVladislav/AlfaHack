package org.alfahack.core.domain;

import java.util.Map;

public record Session(
    String token,
    Map<String, Object> account
) {
}
