package org.alfahack.features.assistant.adapter.in.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.alfahack.core.domain.User;
import org.alfahack.features.assistant.port.AssistantUseCases;
import org.alfahack.features.auth.adapter.in.web.HttpAuth;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AssistantController {

    private final HttpAuth auth;
    private final AssistantUseCases assistant;

    public AssistantController(
        HttpAuth auth,
        AssistantUseCases assistant
    ) {
        this.auth = auth;
        this.assistant = assistant;
    }

    private User user(
        HttpServletRequest request
    ) {
        return auth.user(request);
    }

    @PostMapping("/assistant/messages")
    public Map<String, Object> chat(
        HttpServletRequest request,
        @RequestBody Map<String, Object> body
    ) {
        return assistant.respond(user(request), body);
    }

    @PostMapping("/assistant/proposals/{id}/apply")
    public Map<String, Object> applyProposal(
        HttpServletRequest request,
        @PathVariable String id,
        @RequestBody Map<String, Object> body
    ) {
        return assistant.apply(user(request), id, body);
    }
}
