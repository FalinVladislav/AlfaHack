package org.alfahack.features.products.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.Map;
import org.alfahack.features.auth.adapter.in.web.HttpAuth;
import org.alfahack.features.products.port.FinancingLimitUseCases;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;

@RestController
@RequestMapping("/api/v1/financing/limits")
public class FinancingLimitsController {

    private final FinancingLimitUseCases service;
    private final HttpAuth auth;
    public FinancingLimitsController(
        FinancingLimitUseCases service,
        HttpAuth auth
    ) {
        this.service = service;
        this.auth = auth;
    }

    @GetMapping
    public Map<String, Object> limits(
        HttpServletRequest request,
        @RequestParam String purpose,
        @RequestParam(required = false) String product_id,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate needed_by
    ) {
        return service.limits(auth.user(request), purpose, product_id, needed_by);
    }
}
