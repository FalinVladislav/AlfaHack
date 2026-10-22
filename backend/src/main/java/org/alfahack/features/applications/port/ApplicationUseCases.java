package org.alfahack.features.applications.port;

import java.util.List;
import java.util.Map;
import org.alfahack.core.domain.*;

public interface ApplicationUseCases {

    List<Map<String, Object>> list(
        User user
    );

    Map<String, Object> raw(
        User user,
        String id
    );

    Map<String, Object> detail(
        User user,
        String id
    );

    Map<String, Object> submit(
        User user,
        Map<String, Object> body,
        String key
    );

    Map<String, Object> check(
        User user,
        String id,
        String profile
    );

    Map<String, Object> accept(
        User user,
        String id,
        Map<String, Object> body,
        String key
    );

    Map<String, Object> decline(
        User user,
        String id
    );

    Map<String, Object> sign(
        User user,
        String id,
        Map<String, Object> body,
        String key
    );

    Map<String, Object> disburse(
        User user,
        String id,
        String key
    );

    Map<String, Object> contract(
        User user,
        String id
    );

    List<Map<String, Object>> loans(
        User user
    );

    Map<String, Object> loan(
        User user,
        String id
    );
}
