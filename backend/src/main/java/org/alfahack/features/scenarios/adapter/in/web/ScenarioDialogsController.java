package org.alfahack.features.scenarios.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.alfahack.core.domain.Util;
import org.alfahack.features.auth.adapter.in.web.HttpAuth;
import org.alfahack.features.drafts.port.DraftUseCases;
import org.alfahack.features.scenarios.port.ScenarioDialogUseCases;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ScenarioDialogsController {

    private final HttpAuth auth;
    private final ScenarioDialogUseCases service;
    private final DraftUseCases drafts;
    public ScenarioDialogsController(
        HttpAuth auth,
        ScenarioDialogUseCases service,
        DraftUseCases drafts
    ) {
        this.auth = auth;
        this.service = service;
        this.drafts = drafts;
    }

    @GetMapping("/drafts/{id}/scenario-dialog")
    public Map<String, Object> get(
        HttpServletRequest request,
        @PathVariable String id
    ) {
        return service.get(auth.user(request), id);
    }

    @PostMapping("/drafts/{id}/scenario-dialog/messages")
    public Map<String, Object> send(
        HttpServletRequest request,
        @PathVariable String id,
        @RequestBody Map<String, Object> body
    ) {
        return service.send(auth.user(request), id, body);
    }

    @PostMapping("/drafts/{id}/scenario-dialog/confirm")
    public Map<String, Object> confirm(
        HttpServletRequest request,
        @PathVariable String id,
        @RequestBody Map<String, Object> body
    ) {
        return service.confirm(auth.user(request), id, body);
    }

    @PostMapping("/drafts/{id}/scenario-dialog/prepare")
    public Map<String, Object> prepare(
        HttpServletRequest request,
        @PathVariable String id,
        @RequestBody Map<String, Object> body
    ) {
        return service.prepare(auth.user(request), id, body);
    }

    @PostMapping("/drafts/{id}/scenario-dialog/retry")
    public Map<String, Object> retry(
        HttpServletRequest request,
        @PathVariable String id
    ) {
        return service.retry(auth.user(request), id);
    }

    @PostMapping("/quotes/{id}/business-scenarios")
    public Map<String, Object> calculate(
        HttpServletRequest request,
        @PathVariable String id,
        @RequestBody Map<String, Object> body
    ) {
        var u = auth.user(request);
        return service.calculate(u, drafts.quoteById(u, id),
            Util.number(body.get("plan_version"), 1, Integer.MAX_VALUE, "plan_version"));
    }
}
