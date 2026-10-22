package org.alfahack.features.scoring.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.alfahack.core.domain.User;
import org.alfahack.features.auth.adapter.in.web.HttpAuth;
import org.alfahack.features.scoring.port.ProfileCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ScoringController {

    private final HttpAuth auth;
    private final ProfileCatalog profiles;

    public ScoringController(
        HttpAuth auth,
        ProfileCatalog profiles
    ) {
        this.auth = auth;
        this.profiles = profiles;
    }

    private User user(
        HttpServletRequest request
    ) {
        return auth.user(request);
    }

    @GetMapping("/synthetic/profiles")
    public List<Map<String, Object>> syntheticProfiles(
        HttpServletRequest request
    ) {
        User u = user(request);
        u.staff();
        return profiles.catalog();
    }
}
