package org.alfahack.features.drafts.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.alfahack.core.domain.User;
import org.alfahack.features.auth.adapter.in.web.HttpAuth;
import org.alfahack.features.drafts.port.DraftUseCases;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class DraftsController {

    private final HttpAuth auth;
    private final DraftUseCases drafts;

    public DraftsController(
        HttpAuth auth,
        DraftUseCases drafts
    ) {
        this.auth = auth;
        this.drafts = drafts;
    }

    private User user(
        HttpServletRequest request
    ) {
        return auth.user(request);
    }

    @GetMapping("/drafts")
    public List<Map<String, Object>> drafts(
        HttpServletRequest request
    ) {
        return drafts.list(user(request));
    }

    @PostMapping("/drafts")
    public Map<String, Object> createDraft(
        HttpServletRequest request
    ) {
        return drafts.create(user(request));
    }

    @GetMapping("/drafts/{id}")
    public Map<String, Object> draft(
        HttpServletRequest request,
        @PathVariable String id
    ) {
        return drafts.get(user(request), id);
    }

    @PatchMapping("/drafts/{id}")
    public Map<String, Object> updateDraft(
        HttpServletRequest request,
        @PathVariable String id,
        @RequestBody Map<String, Object> body
    ) {
        return drafts.update(user(request), id, body);
    }

    @PostMapping("/drafts/{id}/quotes")
    public Map<String, Object> quote(
        HttpServletRequest request,
        @PathVariable String id,
        @RequestBody Map<String, Object> body
    ) {
        return drafts.quote(user(request), id, body);
    }
}
