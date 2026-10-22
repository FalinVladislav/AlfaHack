package org.alfahack.features.auth.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.alfahack.core.domain.User;
import org.alfahack.core.domain.Util;
import org.alfahack.features.auth.adapter.in.web.HttpAuth;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final HttpAuth auth;

    public AuthController(
        HttpAuth auth
    ) {
        this.auth = auth;
    }

    private User user(
        HttpServletRequest request
    ) {
        return auth.user(request);
    }

    @PostMapping("/auth/sessions")
    public Map<String, Object> login(
        @RequestBody Map<String, Object> body,
        HttpServletResponse response
    ) {
        return auth.login(Util.required(body, "login"), Util.required(body, "password"), response);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        auth.logout(user(request), request, response);
        return Map.of("ok", true);
    }

    @GetMapping("/me")
    public Map<String, Object> me(
        HttpServletRequest request
    ) {
        return auth.me(user(request));
    }
}
