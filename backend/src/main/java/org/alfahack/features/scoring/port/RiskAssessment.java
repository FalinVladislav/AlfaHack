package org.alfahack.features.scoring.port;

import java.math.BigDecimal;
import java.util.Map;
import org.alfahack.core.domain.*;

public interface RiskAssessment {

    Map<String, Object> assess(
        String key,
        BigDecimal amount,
        BigDecimal newPayment
    );

    Map<String, Object> assess(
        Profile profile,
        BigDecimal amount,
        BigDecimal newPayment
    );
}
