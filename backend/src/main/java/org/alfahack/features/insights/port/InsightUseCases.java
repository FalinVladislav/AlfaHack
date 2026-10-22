package org.alfahack.features.insights.port;

import java.util.Map;
import org.alfahack.core.domain.*;

public interface InsightUseCases {

    Map<String, Object> cashflow(
        User user
    );

}
