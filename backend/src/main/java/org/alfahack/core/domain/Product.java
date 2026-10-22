package org.alfahack.core.domain;

import java.math.BigDecimal;

public record Product(
    String id,
    String name,
    String purpose,
    BigDecimal min,
    BigDecimal max,
    int minTerm,
    int maxTerm,
    BigDecimal rate,
    String unit
) {
}
