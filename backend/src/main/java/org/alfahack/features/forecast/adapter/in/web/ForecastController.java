package org.alfahack.features.forecast.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.alfahack.core.domain.User;
import org.alfahack.core.domain.Util;
import org.alfahack.features.auth.adapter.in.web.HttpAuth;
import org.alfahack.features.drafts.port.DraftUseCases;
import org.alfahack.features.forecast.port.ForecastUseCases;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ForecastController {

    private final HttpAuth auth;
    private final DraftUseCases drafts;
    private final ForecastUseCases forecast;
    private final org.alfahack.features.forecast.port.CreditScenarioUseCases scenarios;

    public ForecastController(
        HttpAuth auth,
        DraftUseCases drafts,
        ForecastUseCases forecast,
        org.alfahack.features.forecast.port.CreditScenarioUseCases scenarios
    ) {
        this.auth = auth;
        this.drafts = drafts;
        this.forecast = forecast;
        this.scenarios = scenarios;
    }

    private User user(
        HttpServletRequest request
    ) {
        return auth.user(request);
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(
        HttpServletRequest request
    ) {
        return forecast.dashboard(user(request));
    }

    @GetMapping("/cashflow/forecast")
    public Map<String, Object> forecast(
        HttpServletRequest request,
        @RequestParam(defaultValue = "30D") String horizon,
        @RequestParam(defaultValue = "BASE") String scenario
    ) {
        return forecast.forecast(user(request), horizon, scenario);
    }

    @PostMapping("/quotes/{id}/analysis")
    public Map<String, Object> analysis(
        HttpServletRequest request,
        @PathVariable String id,
        @RequestBody Map<String, Object> body
    ) {
        User u = user(request);
        return forecast.analysis(u, drafts.quoteById(u, id),
            Util.string(body, "horizon") == null ? "30D" : Util.string(body, "horizon"),
            Util.string(body, "scenario") == null ? "BASE" : Util.string(body, "scenario"));
    }

    @PostMapping("/quotes/{id}/scenarios")
    public Map<String, Object> scenarios(
        HttpServletRequest request,
        @PathVariable String id,
        @RequestBody Map<String, Object> body
    ) {
        User u = user(request);
        return scenarios.simulate(u, drafts.quoteById(u, id), body);
    }
}
