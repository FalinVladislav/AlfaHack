package org.alfahack;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import org.alfahack.core.domain.User;
import org.alfahack.core.errors.DomainException;
import org.alfahack.core.transaction.UnitOfWork;
import org.alfahack.features.assistant.port.AssistantUseCases;
import org.alfahack.features.drafts.port.DraftUseCases;
import org.alfahack.features.drafts.port.DraftsRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:modelapitest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa", "spring.datasource.password="
})
class AssistantIntegrationTest {

    private static final HttpServer SERVER = server();
    @Autowired
    DraftUseCases drafts;
    @Autowired
    AssistantUseCases assistant;
    @Autowired
    UnitOfWork transactions;
    @Autowired
    DraftsRepository repository;

    private static HttpServer server() {
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/v1/chat", exchange -> {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(request.contains("\"instructions\":"));
                assertTrue(request.contains("\"message\":"));
                String answer = "{\"intent\":\"COLLECT_NEED\",\"answer\":\"Проверьте параметры\",\"proposed_fields\":{\"amount\":2000000,\"term_months\":36,\"purpose\":\"EXPANSION\"}}";
                byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            http.start();
            return http;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @DynamicPropertySource
    static void modelApi(
        DynamicPropertyRegistry registry
    ) {
        registry.add("app.model-api.url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @Test
    void proposalIsAppliedOnlyAfterConfirmation() {
        var user = new User("stable", "CLIENT", "STABLE", "test");
        var draft = drafts.create(user);
        var answer = assistant.respond(user,
            Map.of("draft_id", draft.get("id"), "message", "Нужно два миллиона на 36 месяцев"));
        assertEquals(true, answer.get("available"));
        assertEquals(1, drafts.get(user, (String) draft.get("id")).get("version"));
        @SuppressWarnings("unchecked")
        Map<String, Object> proposal = (Map<String, Object>) answer.get("proposal");
        assertEquals("CONFIRMATION_REQUIRED", assertThrows(DomainException.class,
            () -> assistant.apply(user, (String) proposal.get("id"),
                Map.of("expected_version", 1, "confirmed", false))).code);
        assertEquals("PROPOSAL_STALE", assertThrows(DomainException.class,
            () -> assistant.apply(user, (String) proposal.get("id"),
                Map.of("expected_version", 2, "confirmed", true))).code);
        var updated = assistant.apply(user, (String) proposal.get("id"),
            Map.of("expected_version", 1, "confirmed", true));
        assertEquals("2000000.00", updated.get("amount").toString());
        assertEquals(2, updated.get("version"));
    }

    @Test
    void nestedUseCasesShareTransactionAndRollbackTogether() {
        var user = new User("stable", "CLIENT", "STABLE", "test");
        int before = drafts.list(user).size();
        assertThrows(IllegalStateException.class, () -> transactions.required(() -> {
            drafts.create(user);
            repository.insert("rollback-test", "STABLE", LocalDateTime.now());
            throw new IllegalStateException("Rollback both writes");
        }));
        assertEquals(before, drafts.list(user).size());
        assertTrue(repository.findOwned("rollback-test", "STABLE").isEmpty());
    }
}
