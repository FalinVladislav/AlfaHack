package org.alfahack.features.applications.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.alfahack.core.domain.User;
import org.alfahack.core.domain.Util;
import org.alfahack.features.applications.port.ApplicationUseCases;
import org.alfahack.features.auth.adapter.in.web.HttpAuth;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ApplicationsController {

    private final HttpAuth auth;
    private final ApplicationUseCases apps;

    public ApplicationsController(
        HttpAuth auth,
        ApplicationUseCases apps
    ) {
        this.auth = auth;
        this.apps = apps;
    }

    private User user(
        HttpServletRequest request
    ) {
        return auth.user(request);
    }

    @GetMapping("/applications")
    public List<Map<String, Object>> applications(
        HttpServletRequest request
    ) {
        return apps.list(user(request));
    }

    @PostMapping("/applications")
    public Map<String, Object> submit(
        HttpServletRequest request,
        @RequestBody Map<String, Object> body,
        @RequestHeader(value = "Idempotency-Key", required = false) String key
    ) {
        return apps.submit(user(request), body, key);
    }

    @GetMapping("/applications/{id}")
    public Map<String, Object> application(
        HttpServletRequest request,
        @PathVariable String id
    ) {
        return apps.detail(user(request), id);
    }

    @PostMapping("/applications/{id}/checks")
    public Map<String, Object> check(
        HttpServletRequest request,
        @PathVariable String id,
        @RequestBody(required = false) Map<String, Object> body
    ) {
        return apps.check(user(request), id, body == null ? null : Util.string(body, "demo_profile"));
    }

    @PostMapping("/staff/applications/{id}/claim")
    public Map<String, Object> claim(
        HttpServletRequest request,
        @PathVariable String id,
        @RequestBody Map<String, Object> body
    ) {
        return apps.claim(user(request), id, Util.number(body.get("expected_version"), 1,
            Integer.MAX_VALUE, "expected_version"));
    }

    @PostMapping("/staff/applications/{id}/preview")
    public Map<String, Object> preview(
        HttpServletRequest request,
        @PathVariable String id,
        @RequestBody Map<String, Object> body
    ) {
        return apps.preview(user(request), id, body);
    }

    @PostMapping("/staff/applications/{id}/decision")
    public Map<String, Object> decision(
        HttpServletRequest request,
        @PathVariable String id,
        @RequestBody Map<String, Object> body
    ) {
        return apps.decide(user(request), id, body);
    }

    @PostMapping("/applications/{id}/withdraw")
    public Map<String, Object> withdraw(
        HttpServletRequest request,
        @PathVariable String id,
        @RequestBody Map<String, Object> body
    ) {
        return apps.withdraw(user(request), id, Util.number(body.get("expected_version"), 1,
            Integer.MAX_VALUE, "expected_version"));
    }

    @PostMapping("/applications/{id}/copy")
    public Map<String, Object> copy(
        HttpServletRequest request,
        @PathVariable String id
    ) {
        return apps.copyToDraft(user(request), id);
    }

    @GetMapping("/application-notifications")
    public List<Map<String, Object>> notifications(HttpServletRequest request) {
        return apps.notifications(user(request));
    }

    @PostMapping("/application-notifications/{id}/read")
    public List<Map<String, Object>> readNotification(
        HttpServletRequest request,
        @PathVariable String id
    ) {
        return apps.readNotification(user(request), id);
    }

    @PostMapping("/applications/{id}/offer/accept")
    public Map<String, Object> accept(
        HttpServletRequest request,
        @PathVariable String id,
        @RequestBody Map<String, Object> body,
        @RequestHeader(value = "Idempotency-Key", required = false) String key
    ) {
        return apps.accept(user(request), id, body, key);
    }

    @PostMapping("/applications/{id}/offer/decline")
    public Map<String, Object> decline(
        HttpServletRequest request,
        @PathVariable String id
    ) {
        return apps.decline(user(request), id);
    }

    @GetMapping("/applications/{id}/contract")
    public Map<String, Object> contract(
        HttpServletRequest request,
        @PathVariable String id
    ) {
        return apps.contract(user(request), id);
    }

    @PostMapping("/applications/{id}/contract/sign")
    public Map<String, Object> sign(
        HttpServletRequest request,
        @PathVariable String id,
        @RequestBody Map<String, Object> body,
        @RequestHeader(value = "Idempotency-Key", required = false) String key
    ) {
        return apps.sign(user(request), id, body, key);
    }

    @PostMapping("/applications/{id}/disburse")
    public Map<String, Object> disburse(
        HttpServletRequest request,
        @PathVariable String id,
        @RequestHeader(value = "Idempotency-Key", required = false) String key
    ) {
        return apps.disburse(user(request), id, key);
    }

    @GetMapping("/loans")
    public List<Map<String, Object>> loans(
        HttpServletRequest request
    ) {
        return apps.loans(user(request));
    }

    @GetMapping("/loans/{id}")
    public Map<String, Object> loan(
        HttpServletRequest request,
        @PathVariable String id
    ) {
        return apps.loan(user(request), id);
    }

    @GetMapping("/staff/applications")
    public List<Map<String, Object>> staffApplications(
        HttpServletRequest request
    ) {
        User u = user(request);
        u.staff();
        return apps.list(u);
    }

    @GetMapping("/staff/applications/page")
    public Map<String, Object> staffApplicationsPage(
        HttpServletRequest request,
        @RequestParam(defaultValue = "waiting") String tab,
        @RequestParam(defaultValue = "") String query,
        @RequestParam(defaultValue = "") String status,
        @RequestParam(defaultValue = "") String risk,
        @RequestParam(defaultValue = "") String assignee,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "8") int size
    ) {
        return apps.pageStaff(user(request), tab, query, status, risk, assignee,
            Math.max(1, page), Math.max(1, Math.min(50, size)));
    }

    @GetMapping("/staff/applications/{id}")
    public Map<String, Object> staffApplication(
        HttpServletRequest request,
        @PathVariable String id
    ) {
        User u = user(request);
        u.staff();
        return apps.detail(u, id);
    }
}
