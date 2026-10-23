package org.alfahack.features.forecast.port;

import java.util.Map;
import org.alfahack.core.domain.User;

public interface CreditScenarioUseCases {

    Map<String, Object> simulatePlan(
        User user,
        Map<String, Object> quote,
        Map<String, Object> effects
    );

    Map<String, Object> simulate(
        User user,
        Map<String, Object> quote,
        Map<String, Object> assumptions
    );
}
