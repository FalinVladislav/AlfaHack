package org.alfahack.features.insights.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.alfahack.features.auth.adapter.in.web.HttpAuth;
import org.alfahack.features.insights.port.HypothesisUseCases;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class HypothesesController {

    private final HttpAuth auth;
    private final HypothesisUseCases service;
    public HypothesesController(
        HttpAuth auth,
        HypothesisUseCases service
    ) {
        this.auth = auth;
        this.service = service;
    }

    @GetMapping("/hypotheses")
    public Map<String, Object> list(HttpServletRequest request) {
        return service.list(auth.user(request));
    }

    @GetMapping("/hypotheses/{id}")
    public Map<String, Object> detail(
        HttpServletRequest request,
        @PathVariable String id
    ) {
        return service.detail(auth.user(request), id);
    }

    @PostMapping("/hypotheses/refresh")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> refresh(HttpServletRequest request) {
        return service.retry(auth.user(request));
    }

    @GetMapping("/notifications")
    public List<Map<String, Object>> notifications(HttpServletRequest request) {
        return service.notifications(auth.user(request));
    }

    @PostMapping("/notifications/{id}/read")
    public Map<String, Object> read(
        HttpServletRequest request,
        @PathVariable String id
    ) {
        service.read(auth.user(request), id);
        return Map.of("read", true);
    }
}
