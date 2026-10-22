package org.alfahack.features.finance.port;

import java.util.List;
import java.util.Map;
import org.alfahack.core.domain.User;

public interface FinanceUseCases {

    Map<String, Object> overview(User user);

    List<Map<String, Object>> loans(User user);
}
