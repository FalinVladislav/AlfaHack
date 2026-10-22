package org.alfahack.features.goals.port;

import java.util.List;
import java.util.Map;
import org.alfahack.core.domain.User;

public interface GoalUseCases {

    List<Map<String, Object>> list(User user);

    Map<String, Object> resolve(
        User user,
        Map<String, Object> body
    );

    void validate(
        String goalId,
        String text,
        String purpose
    );
}
