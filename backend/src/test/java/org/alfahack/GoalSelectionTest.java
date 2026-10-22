package org.alfahack;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.alfahack.core.domain.User;
import org.alfahack.core.errors.DomainException;
import org.alfahack.features.assistant.port.LanguageModel;
import org.alfahack.features.goals.application.GoalSelection;
import org.alfahack.features.products.application.Products;
import org.junit.jupiter.api.Test;

class GoalSelectionTest {

    private final User user = new User("stable", "CLIENT", "STABLE", "test");
    static class Model implements LanguageModel {

        boolean enabled = true, fail = false;
        int calls;
        Map<String, Object> response = Map.of();
        public boolean available() {
            return enabled;
        }

        public Map<String, Object> complete(
            String instructions,
            String text
        ) throws Exception {
            calls++;
            assertTrue(instructions.contains("CLARIFY_GOAL"));
            assertTrue(instructions.contains("CASH_GAP"));
            if (fail)
                throw new IllegalStateException("provider failure");
            return response;
        }
    }
    @Test
    void predefinedGoalsMapToCatalogProductsWithoutModelCalls() {
        var model = new Model();
        var service = new GoalSelection(new Products(), model);
        assertEquals(6, service.list(user).size());
        for (var goal : service.list(user)) {
            var result = service.resolve(user, Map.of("goal_id", goal.get("id")));
            assertEquals(goal.get("purpose"), result.get("purpose"));
            assertEquals(goal.get("product_ids"), result.get("product_ids"));
            assertEquals("CATALOG", result.get("source"));
        }
        assertEquals(0, model.calls);
    }

    @Test
    void modelRoutesByMeaningButCannotInjectAnUnknownProductOrAmount() {
        var model = new Model();
        var service = new GoalSelection(new Products(), model);
        model.response = Map.of("intent", "CLASSIFY_GOAL", "answer", "OK",
            "proposed_fields", Map.of("purpose", "CASH_GAP", "amount", 99999999, "product_id", "INVENTED"));
        var result = service.resolve(user,
            Map.of("goal_id", "OTHER", "text", "Аренда сейчас, клиент заплатит через две недели"));
        assertEquals("READY", result.get("status"));
        assertEquals("LLM", result.get("source"));
        assertEquals("CASH_GAP", result.get("purpose"));
        assertEquals(List.of("OVERDRAFT"), result.get("product_ids"));
        assertFalse(result.containsKey("amount"));
        model.response = Map.of("intent", "CLASSIFY_GOAL", "proposed_fields", Map.of("purpose", "MORTGAGE"));
        assertEquals("NEEDS_CLARIFICATION",
            service.resolve(user, Map.of("text", "Хочу купить новую квартиру")).get("status"));
    }

    @Test
    void ambiguityAndUnsupportedRequestsDoNotSilentlyChooseAPurpose() {
        var model = new Model();
        var service = new GoalSelection(new Products(), model);
        model.response = Map.of("intent", "CLARIFY_GOAL", "answer", "Какая задача первоочередная?", "proposed_fields",
            Map.of());
        var result = service.resolve(user, Map.of("text", "Нужно оборудование и деньги на зарплаты"));
        assertEquals("NEEDS_CLARIFICATION", result.get("status"));
        assertFalse(result.containsKey("purpose"));
        assertEquals("Какая задача первоочередная?", result.get("question"));
        model.response = Map.of("intent", "UNSUPPORTED_GOAL", "proposed_fields", Map.of());
        assertEquals("UNSUPPORTED",
            service.resolve(user, Map.of("text", "Хочу кредит на личный отпуск")).get("status"));
    }

    @Test
    void conservativeFallbackWorksDuringProviderFailure() {
        var model = new Model();
        model.fail = true;
        var service = new GoalSelection(new Products(), model);
        assertEquals("WORKING_CAPITAL", service.resolve(user, Map.of("text", "Нужна закупка сырья")).get("purpose"));
        model.enabled = false;
        assertEquals("EXPANSION", service.resolve(user, Map.of("text", "Нужно новое оборудование")).get("purpose"));
        assertEquals("CASH_GAP", service.resolve(user, Map.of("text", "Закрыть кассовый разрыв")).get("purpose"));
        for (String text : List.of("Нужно оборудование и сырье", "Не нужно оборудование", "Помогите моему бизнесу",
            "Аренду надо оплатить сейчас, деньги придут через две недели"))
            assertEquals("NEEDS_CLARIFICATION", service.resolve(user, Map.of("text", text)).get("status"), text);
    }

    @Test
    void invalidRequestsAndMismatchedSelectionsAreRejected() {
        var service = new GoalSelection(new Products(), new Model());
        assertThrows(DomainException.class, () -> service.resolve(user, Map.of("text", "x")));
        assertThrows(DomainException.class, () -> service.resolve(user, Map.of("text", "x".repeat(501))));
        assertThrows(DomainException.class, () -> service.resolve(user, Map.of("goal_id", 1, "text", "Оборудование")));
        assertThrows(DomainException.class, () -> service.resolve(user, Map.of("goal_id", "UNKNOWN")));
        assertThrows(DomainException.class, () -> service.validate("EQUIPMENT", "Купить оборудование", "CASH_GAP"));
        assertThrows(DomainException.class, () -> service.validate("EQUIPMENT", "Другая цель", "EXPANSION"));
        assertThrows(DomainException.class, () -> service.list(new User("staff", "STAFF", null, "test")));
    }
}
