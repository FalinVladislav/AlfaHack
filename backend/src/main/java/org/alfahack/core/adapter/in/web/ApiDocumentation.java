package org.alfahack.core.adapter.in.web;

import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ApiDocumentation {

    @GetMapping({"/swagger-ui", "/swagger-ui/", "/swagger-ui.html"})
    public ResponseEntity<Void> swaggerUi() {
        // Keep redirects on the public origin when Vite or nginx proxies the backend.
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(URI.create("/swagger-ui/index.html"))
            .build();
    }
}
