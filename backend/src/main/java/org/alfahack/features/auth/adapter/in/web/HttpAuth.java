package org.alfahack.features.auth.adapter.in.web;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.alfahack.core.domain.User;
import org.alfahack.core.errors.DomainException;
import org.alfahack.features.auth.port.AuthUseCases;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class HttpAuth {

    private final AuthUseCases service;
    private final boolean secureCookie;

    public HttpAuth(
        AuthUseCases service,
        @Value("${app.secure-cookie}") boolean secureCookie
    ) {
        this.service = service;
        this.secureCookie = secureCookie;
    }

    public Map<String, Object> login(
        String key,
        String password,
        HttpServletResponse response
    ) {
        var session = service.login(key, password);
        Cookie cookie = cookie(session.token(), 60 * 60 * 24);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
        return session.account();
    }

    public void logout(
        User user,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        service.logout(token(request));
        response.addCookie(cookie("", 0));
    }

    public User user(
        HttpServletRequest request
    ) {
        User user = service.authenticate(token(request));
        if (!java.util.Set.of("GET", "HEAD", "OPTIONS").contains(request.getMethod())
            && !user.csrf().equals(request.getHeader("X-CSRF-Token")))
            throw DomainException.forbidden("CSRF", "Обновите страницу и повторите действие");
        return user;
    }

    public Map<String, Object> me(
        User user
    ) {
        return service.me(user);
    }

    private Cookie cookie(
        String token,
        int age
    ) {
        Cookie cookie = new Cookie("cabinet_session", token);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(age);
        cookie.setSecure(secureCookie);
        return cookie;
    }

    private String token(
        HttpServletRequest request
    ) {
        if (request.getCookies() != null)
            for (Cookie cookie : request.getCookies())
                if (cookie.getName().equals("cabinet_session"))
                    return cookie.getValue();
        return null;
    }
}
