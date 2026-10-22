package org.alfahack.core.domain;

import java.math.BigDecimal;

public record Profile(
    String key,
    String sector,
    int businessMonths,
    int founders,
    BigDecimal monthlyRevenue,
    BigDecimal previousRevenue,
    BigDecimal monthlyProfit,
    BigDecimal monthlyDebt,
    BigDecimal currentAssets,
    BigDecimal currentLiabilities,
    BigDecimal collateralValue,
    int overdueDays,
    boolean sme,
    String creditHistory
) {
}
