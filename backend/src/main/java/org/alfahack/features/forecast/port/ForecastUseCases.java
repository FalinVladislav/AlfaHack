package org.alfahack.features.forecast.port;

import java.util.Map;
import org.alfahack.core.domain.*;

public interface ForecastUseCases {

    Map<String, Object> dashboard(
        User user
    );

    Map<String, Object> forecast(
        User user,
        String horizon,
        String scenario
    );

    Map<String, Object> analysis(
        User user,
        Map<String, Object> quote,
        String horizon,
        String scenario
    );
}
