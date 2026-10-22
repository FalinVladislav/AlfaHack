package org.alfahack.features.finance.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.alfahack.features.auth.adapter.in.web.HttpAuth;
import org.alfahack.features.finance.port.FinanceUseCases;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/finance")
public class FinanceController {

    private final HttpAuth auth;
    private final FinanceUseCases finance;
    public FinanceController(
        HttpAuth auth,
        FinanceUseCases finance
    ) {
        this.auth = auth;
        this.finance = finance;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(HttpServletRequest request) {
        return finance.overview(auth.user(request));
    }

    @GetMapping("/loans")
    public List<Map<String, Object>> loans(HttpServletRequest request) {
        return finance.loans(auth.user(request));
    }
}
