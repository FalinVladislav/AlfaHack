package org.alfahack.features.products.port;

import java.time.LocalDate;
import java.util.Map;
import org.alfahack.core.domain.User;

public interface FinancingLimitUseCases {

    Map<String, Object> limits(
        User user,
        String purpose,
        String productId,
        LocalDate neededBy
    );
}
