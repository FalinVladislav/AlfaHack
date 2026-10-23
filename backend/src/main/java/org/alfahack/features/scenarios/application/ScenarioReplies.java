package org.alfahack.features.scenarios.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.alfahack.core.serialization.JsonCodec;

/**
 * Validate provider messages before they enter the conversation or execute
 * tools.
 */
public final class ScenarioReplies {

    private static final Set<String> TOOLS = Set.of("get_business_context", "get_missing_inputs", "simulate_scenarios");
    private final JsonCodec json;

    public ScenarioReplies(JsonCodec json) {
        this.json = json;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> validate(Map<String, Object> response) {
        if (response == null || !(response.get("message") instanceof Map<?, ?> raw)
            || !"assistant".equals(raw.get("role")))
            throw new IllegalArgumentException("INVALID_MODEL_ROLE");
        if (response.containsKey("finish_reason")
            && !Set.of("stop", "tool_calls").contains(String.valueOf(response.get("finish_reason"))))
            throw new IllegalArgumentException("INCOMPLETE_MODEL_RESPONSE");
        Object content = raw.get("content");
        if (content != null && (!(content instanceof String text) || text.length() > 6000))
            throw new IllegalArgumentException("INVALID_MODEL_CONTENT");
        Object rawCalls = raw.get("tool_calls");
        if (rawCalls != null && !(rawCalls instanceof List<?>))
            throw new IllegalArgumentException("INVALID_MODEL_CALLS");
        var calls = rawCalls == null ? List.of() : (List<?>) rawCalls;
        if (response.containsKey("finish_reason")
            && (!calls.isEmpty() != "tool_calls".equals(response.get("finish_reason"))))
            throw new IllegalArgumentException("INCONSISTENT_FINISH_REASON");
        if (calls.size() > 3)
            throw new IllegalArgumentException("TOO_MANY_MODEL_CALLS");
        var ids = new HashSet<String>();
        var cleanCalls = new ArrayList<Map<String, Object>>();
        for (var value : calls) {
            if (!(value instanceof Map<?, ?> call) || !"function".equals(call.get("type"))
                || !(call.get("id") instanceof String id) || !id.matches("[a-zA-Z0-9_-]{1,200}") || !ids.add(id)
                || !(call.get("function") instanceof Map<?, ?> fn) || !TOOLS.contains(String.valueOf(fn.get("name")))
                || !(fn.get("arguments") instanceof String args) || args.isBlank() || args.length() > 32000)
                throw new IllegalArgumentException("INVALID_MODEL_CALL");
            Map<String, Object> arguments;
            try {
                arguments = json.read(args);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("INVALID_MODEL_ARGUMENTS");
            }
            if (arguments == null || ("get_business_context".equals(fn.get("name"))
                ? !arguments.isEmpty()
                : !arguments.keySet().equals(Set.of("plan")) || !(arguments.get("plan") instanceof Map<?, ?>)))
                throw new IllegalArgumentException("INVALID_MODEL_ARGUMENTS");
            cleanCalls.add(
                Map.of("id", id, "type", "function", "function", Map.of("name", fn.get("name"), "arguments", args)));
        }
        if (calls.isEmpty() && (!(content instanceof String text) || text.isBlank()))
            throw new IllegalArgumentException("EMPTY_MODEL_RESPONSE");
        var result = new LinkedHashMap<String, Object>();
        result.put("role", "assistant");
        result.put("content", content == null ? null : content.toString().strip());
        if (!cleanCalls.isEmpty())
            result.put("tool_calls", cleanCalls);
        return result;
    }

    public boolean finalOnly(
        List<Map<String, Object>> turn,
        int rounds
    ) {
        if (rounds >= 3)
            return true;
        for (var message : turn)
            if ("tool".equals(message.get("role"))) {
                var result = json.read(message.get("content").toString());
                if (Set
                    .of("NEEDS_INPUTS", "INVALID_INPUT", "AWAITING_CONFIRMATION", "REPEATED_CALL",
                        "FINAL_ANSWER_REQUIRED")
                    .contains(String.valueOf(result.get("status"))))
                    return true;
            }
        return false;
    }

    @SuppressWarnings("unchecked")
    public boolean repeated(
        Map<String, Object> call,
        List<Map<String, Object>> messages
    ) {
        var function = (Map<String, Object>) call.get("function");
        for (var message : messages)
            if (message.get("tool_calls") instanceof List<?> calls)
                for (var item : calls) {
                    var previous = (Map<String, Object>) ((Map<?, ?>) item).get("function");
                    if (function.get("name").equals(previous.get("name"))
                        && json.read(function.get("arguments").toString())
                            .equals(json.read(previous.get("arguments").toString())))
                        return true;
                }
        return false;
    }

    public String rejectText(
        String text,
        List<Map<String, Object>> turn,
        boolean planReady
    ) {
        boolean cashflowReady = false, needsInput = !planReady;
        for (var message : turn)
            if ("tool".equals(message.get("role"))) {
                var result = json.read(message.get("content").toString());
                if (result.get("cashflow") instanceof Map<?, ?> cashflow)
                    cashflowReady |= "READY".equals(cashflow.get("status"));
            }
        String normalized = text.toLowerCase(java.util.Locale.ROOT).replace('ё', 'е');
        if (normalized.contains("лимит автоматических шагов") || normalized.contains("get_missing_inputs")
            || normalized.contains("get_business_context") || normalized.contains("simulate_scenarios"))
            return "TECHNICAL_MODEL_TEXT";
        // Explicit negations such as “расчёт пока не готов” are useful explanations,
        // not claims that a simulation succeeded.
        String claims = normalized.replaceAll(
            "(?U)\\b(расчет|сценарии|план)\\s+(?:пока\\s+|еще\\s+)?не\\s+(?:готов\\p{L}*|рассчитан\\p{L}*|сформирован\\p{L}*)",
            "");
        if (!cashflowReady && (claims.matches("(?s).*(расчет|сценарии)[^.!?\\n]{0,35}(готов|рассчитан|сформирован).*")))
            return "UNSUPPORTED_CALCULATION_CLAIM";
        if (!planReady && claims.matches("(?s).*план[^.!?\\n]{0,20}(готов|сформирован).*"))
            return "UNSUPPORTED_PLAN_CLAIM";
        if (needsInput && !text.contains("?") && !normalized.matches(
            "(?s).*(уточните|укажите|расскажите|сообщите|нужны|нужно узнать|не хватает|невозможно|не удалось|недостаточно).*"))
            return "NO_ACTIONABLE_QUESTION";
        if (text.chars().filter(c -> c == '?').count() > 3)
            return "TOO_MANY_QUESTIONS";
        return null;
    }

    public String question(
        List<String> missing,
        boolean equipment
    ) {
        var questions = new ArrayList<String>();
        if (missing.isEmpty() || missing.contains("components") || missing.stream().anyMatch(p -> p.endsWith(".kind")))
            questions.add(equipment
                ? "Какое оборудование хотите купить и какую задачу оно должно решить?"
                : "Что именно изменится в бизнесе и какой результат вы ожидаете?");
        if (missing.isEmpty() || missing.contains("investment"))
            questions.add(equipment ? "Сколько оно стоит с доставкой и установкой?" : "Каков полный бюджет проекта?");
        if (missing.contains("launch_months"))
            questions.add("Когда планируете запустить проект?");
        if (questions.size() < 3 && missing.stream().anyMatch(p -> p.startsWith("components[")))
            questions.add("Какие продажи и текущие расходы есть сейчас и что изменится после проекта?");
        if (questions.size() < 3 && missing.stream().anyMatch(p -> p.startsWith("cashflow")))
            questions.add("Какие расходы будут до запуска и через сколько дней поступает оплата от клиентов?");
        if (questions.isEmpty())
            questions.add("Какие суммы и сроки вы можете подтвердить, а какие пока нужно оценить?");
        return "Чтобы оценить последствия проекта, уточните:\n"
            + String.join("\n", questions.subList(0, Math.min(3, questions.size())));
    }

    public static List<Map<String, Object>> turn(List<Map<String, Object>> history) {
        int start = 0;
        for (int i = history.size() - 1; i >= 0; i--)
            if ("user".equals(history.get(i).get("role")) && history.get(i).containsKey("request_id")) {
                start = i;
                break;
            }
        return history.subList(start, history.size());
    }
}
