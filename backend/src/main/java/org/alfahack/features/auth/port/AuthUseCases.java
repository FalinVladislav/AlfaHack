package org.alfahack.features.auth.port;

import java.util.Map;
import org.alfahack.core.domain.*;

public interface AuthUseCases {

    Session login(
        String key,
        String password
    );

    void ensureCredential(
        String key,
        String password
    );

    User authenticate(
        String token
    );

    void logout(
        String token
    );

    Map<String, Object> me(
        User user
    );
}
