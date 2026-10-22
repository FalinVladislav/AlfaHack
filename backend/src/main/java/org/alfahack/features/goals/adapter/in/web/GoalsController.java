package org.alfahack.features.goals.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.alfahack.features.auth.adapter.in.web.HttpAuth;
import org.alfahack.features.goals.port.GoalUseCases;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/financing")
public class GoalsController {

    private final HttpAuth auth;
    private final GoalUseCases goals;
    public GoalsController(
        HttpAuth auth,
        GoalUseCases goals
    ) {
        this.auth = auth;
        this.goals = goals;
    }

    @GetMapping("/goals")
    public List<Map<String, Object>> list(HttpServletRequest request) {
        return goals.list(auth.user(request));
    }

    @PostMapping("/resolve-goal")
    public Map<String, Object> resolve(
        HttpServletRequest request,
        @RequestBody Map<String, Object> body
    ) {
        return goals.resolve(auth.user(request), body);
    }
}
