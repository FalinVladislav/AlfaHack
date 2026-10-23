package org.alfahack;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.alfahack.core.adapter.out.serialization.JacksonJsonCodec;
import org.alfahack.features.scenarios.application.ScenarioReplies;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ScenarioRepliesTest {

    final JacksonJsonCodec json = new JacksonJsonCodec(new ObjectMapper());
    final ScenarioReplies replies = new ScenarioReplies(json);

    static Map<String, Object> call(
        String id,
        String name,
        String args
    ) {
        return Map.of("id", id, "type", "function", "function", Map.of("name", name, "arguments", args));
    }

    static Stream<Map<String, Object>> invalidResponses() {
        var valid = call("call-1", "get_business_context", "{}");
        return Stream.of(
            Map.of(), Map.of("message", Map.of("role", "user", "content", "Привет")),
            Map.of("message", Map.of("role", "assistant", "content", 123)),
            Map.of("message", Map.of("role", "assistant", "content", " ")),
            Map.of("message", Map.of("role", "assistant", "content", "x".repeat(6001))),
            Map.of("message", Map.of("role", "assistant", "tool_calls", "bad")),
            Map.of("message", Map.of("role", "assistant", "tool_calls", List.of(valid, valid))),
            Map.of("message", Map.of("role", "assistant", "tool_calls", List.of(valid, valid, valid, valid))),
            Map.of("message", Map.of("role", "assistant", "tool_calls", List.of(call("c", "execute_sql", "{}")))),
            Map.of("message",
                Map.of("role", "assistant", "tool_calls", List.of(call("c", "get_business_context", "[]")))),
            Map.of("message",
                Map.of("role", "assistant", "tool_calls", List.of(call("c", "get_business_context", "broken")))),
            Map.of("message",
                Map.of("role", "assistant", "tool_calls",
                    List.of(call("c", "get_business_context", "{\"company_id\":\"other\"}")))),
            Map.of("message",
                Map.of("role", "assistant", "tool_calls", List.of(call("c", "simulate_scenarios", "{}")))),
            Map.of("finish_reason", "length", "message", Map.of("role", "assistant", "content", "Обрезано")),
            Map.of("finish_reason", "stop", "message", Map.of("role", "assistant", "tool_calls", List.of(valid))),
            Map.of("finish_reason", "tool_calls", "message", Map.of("role", "assistant", "content", "Вопрос?")));
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void rejectsMalformedMessagesBeforeExecutingAnyTool(Map<String, Object> response) {
        assertThrows(IllegalArgumentException.class, () -> replies.validate(response));
    }

    @Test
    void allowsNullToolCallsInAnOtherwiseValidAnswer() {
        var message = new HashMap<String, Object>();
        message.put("role", "assistant");
        message.put("content", " Какое оборудование хотите купить? ");
        message.put("tool_calls", null);
        assertEquals("Какое оборудование хотите купить?", replies.validate(Map.of("message", message)).get("content"));
    }

    @Test
    void detectsRepeatedCallsByParsedArgumentsRatherThanJsonFormatting() {
        var previous = call("old", "get_missing_inputs", "{\"plan\":{\"title\":\"Проект\",\"schema_version\":2}}");
        var next = call("new", "get_missing_inputs", "{ \"plan\": {\"schema_version\":2, \"title\":\"Проект\"} }");
        assertTrue(replies.repeated(next, List.of(Map.of("role", "assistant", "tool_calls", List.of(previous)))));
    }

    @Test
    void toolResultsRequireAnAnswerInsteadOfAnotherToolRound() {
        for (String status : List.of("NEEDS_INPUTS", "INVALID_INPUT", "AWAITING_CONFIRMATION"))
            assertTrue(
                replies.finalOnly(List.of(Map.of("role", "tool", "content", json.write(Map.of("status", status)))), 1));
        assertTrue(replies.finalOnly(List.of(), 3));
        assertFalse(replies.finalOnly(List.of(), 0));
    }

    @Test
    void rejectsUnsupportedReadinessClaimsAndNonAnswers() {
        assertNotNull(replies.rejectText("Расчёт готов, сценарии рассчитаны.", List.of(), false));
        assertNotNull(replies.rejectText("План готов.", List.of(), false));
        assertNotNull(replies.rejectText("Достигнут лимит автоматических шагов.", List.of(), false));
        assertNotNull(replies.rejectText("Сейчас изучу данные.", List.of(), false));
        assertNotNull(replies.rejectText("Цена? Срок? Спрос? Расходы?", List.of(), false));
        assertNull(replies.rejectText("Какое оборудование нужно и сколько оно стоит?", List.of(), false));
        assertNull(replies.rejectText("Расчёт пока не готов: уточните стоимость оборудования.", List.of(), false));
        assertNull(replies.rejectText("Сценарии ещё не рассчитаны. Какова стоимость оборудования?", List.of(), false));
        assertNull(replies.rejectText("План не готов. Какой бюджет?", List.of(), false));
        assertNull(replies.rejectText("Проверьте готовый план.", List.of(), true));
    }
}
