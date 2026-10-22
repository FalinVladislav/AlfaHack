package org.alfahack.features.scoring.port;

import java.util.Map;
import org.alfahack.core.domain.*;

public interface RiskSources {

    Map<String, Object> fetch(
        String key
    );
}
