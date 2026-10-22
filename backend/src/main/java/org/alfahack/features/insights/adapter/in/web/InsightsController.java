package org.alfahack.features.insights.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.alfahack.core.domain.User;
import org.alfahack.features.auth.adapter.in.web.HttpAuth;
import org.alfahack.features.insights.port.InsightUseCases;
import org.alfahack.features.insights.port.HypothesisUseCases;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class InsightsController {

    private final HttpAuth auth;
    private final InsightUseCases insights;
    private final HypothesisUseCases hypotheses;

    public InsightsController(
        HttpAuth auth,
        InsightUseCases insights,
        HypothesisUseCases hypotheses
    ) {
        this.auth = auth;
        this.insights = insights;
        this.hypotheses = hypotheses;
    }

    private User user(
        HttpServletRequest request
    ) {
        return auth.user(request);
    }

    @GetMapping("/insights")
    public Map<String, Object> insights(
        HttpServletRequest request
    ) {
        return hypotheses.list(user(request));
    }

    @GetMapping("/cashflow/summary")
    public Map<String, Object> cashflow(
        HttpServletRequest request
    ) {
        return insights.cashflow(user(request));
    }
}
