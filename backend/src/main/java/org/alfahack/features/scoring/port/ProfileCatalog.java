package org.alfahack.features.scoring.port;

import java.util.List;
import java.util.Map;
import org.alfahack.core.domain.*;

public interface ProfileCatalog {

    Profile get(
        String key
    );

    List<Map<String, Object>> catalog();
}
