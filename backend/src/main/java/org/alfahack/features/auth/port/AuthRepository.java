package org.alfahack.features.auth.port;

import java.time.LocalDateTime;
import java.util.Map;

public interface AuthRepository {

    Map<String, Object> account(
        String key
    );

    Map<String, Object> credential(
        String key
    );

    void saveCredential(
        String key,
        String salt,
        String hash
    );

    void createSession(
        String token,
        String key,
        String csrf,
        LocalDateTime createdAt
    );

    Map<String, Object> session(
        String token,
        LocalDateTime notBefore
    );

    void deleteSession(
        String token
    );
}
