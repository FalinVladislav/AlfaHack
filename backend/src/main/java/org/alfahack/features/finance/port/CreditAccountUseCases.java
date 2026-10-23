package org.alfahack.features.finance.port;

import java.util.Map;
import org.alfahack.core.domain.User;

public interface CreditAccountUseCases {

    Map<String, Object> load(
        User user,
        String search,
        String source,
        String from,
        String to,
        int loanPage,
        int paymentPage,
        int size
    );

    Map<String, Object> rename(
        User user,
        String source,
        String id,
        String name
    );

    Map<String, Object> pay(
        User user,
        String source,
        String id,
        String requestKey,
        String expectedDue
    );
}
