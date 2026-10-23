package org.alfahack;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import java.util.Map;
import org.alfahack.core.domain.User;
import org.alfahack.core.errors.DomainException;
import org.alfahack.core.serialization.JsonCodec;
import org.alfahack.features.drafts.port.DraftUseCases;
import org.alfahack.features.scenarios.port.ScenarioDialogUseCases;
import org.alfahack.features.scenarios.port.ScenarioModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:scenariodialogs;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa", "spring.datasource.password=", "app.scenarios.worker-enabled=false",
        "app.insights.worker-enabled=false"})
@Transactional
@AutoConfigureMockMvc
class ScenarioDialogsIntegrationTest {

    @Autowired
    ScenarioDialogUseCases service;
    @Autowired
    DraftUseCases drafts;
    @Autowired
    JsonCodec json;
    @Autowired
    JdbcTemplate db;
    @Autowired
    MockMvc mvc;
    @MockBean
    ScenarioModel model;
    final User user = new User("stable", "CLIENT", "STABLE", "test");

    @Test
    void productionToolSchemasAreStrictAndCanBeUsedForProviderVerification() throws Exception {
        var tools = org.alfahack.features.scenarios.application.ScenarioTools.definitions();
        for (var tool : tools)
            checkStrict(tool);
        java.nio.file.Files.writeString(java.nio.file.Path.of("target/scenario-model-fixture.json"),
            json.write(Map.of(
                "tools", tools, "plan", BusinessPlanTest.modernPlan(), "instructions",
                org.alfahack.features.scenarios.application.ScenarioTools.INSTRUCTIONS)));
    }

    void checkStrict(Object node) {
        if (node instanceof Map<?, ?> m) {
            if ("object".equals(m.get("type"))) {
                assertEquals(false, m.get("additionalProperties"));
                assertEquals(((Map<?, ?>) m.get("properties")).keySet(),
                    new java.util.HashSet<>((List<?>) m.get("required")));
            }
            m.values().forEach(this::checkStrict);
        } else
            if (node instanceof List<?> list)
                list.forEach(this::checkStrict);
    }
    String id, qid;
    @BeforeEach
    void setup() {
        var draft = drafts.create(user);
        id = draft.get("id").toString();
        draft = drafts.update(user, id, Map.of("expected_version", draft.get("version"), "purpose", "EXPANSION",
            "amount", "1000000", "term_months", 24, "needed_by", "2026-11-01"));
        qid = BusinessPlanTest.rows(drafts.quote(user, id, Map.of("expected_version", draft.get("version"))), "options")
            .stream().filter(o -> o.get("quote_id") != null).findFirst().orElseThrow().get("quote_id").toString();
    }

    Map<String, Object> send(String requestId) {
        return service.send(user, id,
            Map.of("message", "Хочу открыть новую точку", "quote_id", qid, "request_id", requestId));
    }

    Map<String, Object> call(
        String name,
        Map<String, Object> args
    ) {
        return Map.of("message", Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", "call-1", "type",
            "function", "function", Map.of("name", name, "arguments", json.write(args))))));
    }

    Map<String, Object> answer(String text) {
        return Map.of("message", Map.of("role", "assistant", "content", text));
    }

    void propose() throws Exception {
        when(model.complete(anyList(), anyList())).thenReturn(
            call("simulate_scenarios", Map.of("plan", BusinessPlanTest.modernPlan())),
            answer("Проверьте параметры плана."));
        send("request-001");
        service.processNext();
        service.processNext();
    }

    @Test
    void toolLoopHistoryAndExplicitConfirmationSurviveReload() throws Exception {
        propose();
        var state = service.get(user, id);
        assertEquals("IDLE", state.get("status"));
        assertEquals(1, state.get("plan_version"));
        assertEquals(0, state.get("confirmed_version"));
        assertNotNull(state.get("proposed_plan"));
        assertThrows(DomainException.class, () -> service.calculate(user, drafts.quoteById(user, qid), 1));
        var persisted = db.queryForMap("SELECT * FROM business_scenario_dialogs WHERE draft_id=?", id);
        assertTrue(persisted.get("history_json").toString().contains("tool_call_id"));
        assertTrue(persisted.get("history_json").toString().contains("cashflow"));
        service.confirm(user, id, Map.of("plan_version", 1));
        var result = service.calculate(user, drafts.quoteById(user, qid), 1);
        assertEquals("business-dependencies-v2", result.get("model_version"));
        assertEquals(1, service.get(user, id).get("confirmed_version"));
        assertEquals(3, BusinessPlanTest.rows(result, "scenarios").size());
        assertTrue(db.queryForObject("SELECT COUNT(*) FROM business_scenario_runs", Integer.class) >= 2);
        verify(model, times(2)).complete(anyList(), anyList());
    }

    @Test
    void duplicateMessageIsIdempotentAndBusyDialogRejectsNewMessage() {
        send("request-001");
        send("request-001");
        assertEquals(1, BusinessPlanTest.rows(service.get(user, id), "messages").size());
        assertThrows(DomainException.class, () -> send("request-002"));
    }

    @Test
    void completeInputCheckAlsoCreatesAnUnconfirmedProposal() throws Exception {
        when(model.complete(anyList(), anyList()))
            .thenReturn(call("get_missing_inputs", Map.of("plan", BusinessPlanTest.modernPlan())),
                answer("Расчёт готов."));
        send("request-001");
        service.processNext();
        service.processNext();
        var state = service.get(user, id);
        assertNotNull(state.get("proposed_plan"));
        assertEquals(1, state.get("plan_version"));
        assertEquals(0, state.get("confirmed_version"));
        assertNull(state.get("confirmed_plan"));
        assertThrows(DomainException.class, () -> service.calculate(user, drafts.quoteById(user, qid), 1));
    }

    @Test
    void existingCompleteWorkingPlanCanBeRecoveredWithoutModelOrConfirmation() {
        send("request-001");
        String plan = json.write(BusinessPlanTest.modernPlan());
        db.update("UPDATE business_scenario_dialogs SET status='IDLE',working_plan_json=? WHERE draft_id=?", plan, id);
        var state = service.get(user, id);
        assertEquals(true, state.get("ready_to_prepare"));
        assertThrows(DomainException.class, () -> service.prepare(user, id, Map.of("working_plan_hash", "outdated")));
        state = service.prepare(user, id, Map.of("working_plan_hash", state.get("working_plan_hash")));
        assertNotNull(state.get("proposed_plan"));
        assertEquals(1, state.get("plan_version"));
        assertEquals(0, state.get("confirmed_version"));
        service.prepare(user, id, Map.of("working_plan_hash", "already prepared"));
        assertEquals(1, service.get(user, id).get("plan_version"));
        verifyNoInteractions(model);
    }

    @Test
    void missingInputsPersistAsWorkingPlanAndDoNotBecomeConfirmed() throws Exception {
        var partial = BusinessPlanTest.modernPlan();
        BusinessPlanTest.params(BusinessPlanTest.rows(partial, "components").getFirst(), "after").remove("demand");
        when(model.complete(anyList(), anyList())).thenReturn(call("get_missing_inputs", Map.of("plan", partial)),
            answer("Какой спрос ожидаете?"));
        send("request-001");
        service.processNext();
        service.processNext();
        assertNull(service.get(user, id).get("proposed_plan"));
        assertNotNull(db.queryForObject("SELECT working_plan_json FROM business_scenario_dialogs WHERE draft_id=?",
            String.class, id));
        assertThrows(DomainException.class, () -> service.confirm(user, id, Map.of("plan_version", 1)));
    }

    @Test
    void modelFailureCanBeRetriedWithoutLosingTheUserMessage() throws Exception {
        when(model.complete(anyList(), anyList())).thenThrow(new IllegalStateException("sensitive provider error"));
        send("request-001");
        service.processNext();
        var state = service.get(user, id);
        assertEquals("FAILED", state.get("status"));
        assertFalse(state.get("last_error").toString().contains("sensitive"));
        doReturn(answer("Уточните бюджет.")).when(model).complete(anyList(), anyList());
        service.retry(user, id);
        service.processNext();
        assertEquals("IDLE", service.get(user, id).get("status"));
        assertEquals(2, BusinessPlanTest.rows(service.get(user, id), "messages").size());
    }

    @Test
    void changedLoanKeepsProjectEconomicsButChangedGoalRequiresNewConfirmation() throws Exception {
        propose();
        service.confirm(user, id, Map.of("plan_version", 1));
        var d = drafts.get(user, id);
        d = drafts.update(user, id, Map.of("expected_version", d.get("version"), "amount", "1200000"));
        String next = BusinessPlanTest
            .rows(drafts.quote(user, id, Map.of("expected_version", d.get("version"))), "options").stream()
            .filter(o -> o.get("quote_id") != null).findFirst().orElseThrow().get("quote_id").toString();
        assertEquals("READY", service.calculate(user, drafts.quoteById(user, next), 1).get("status"));
        assertThrows(DomainException.class, () -> service.calculate(user, drafts.quoteById(user, qid), 1));
        drafts.update(user, id, Map.of("expected_version", d.get("version"), "purpose", "WORKING_CAPITAL"));
        assertEquals("NOT_STARTED", service.get(user, id).get("status"));
        assertThrows(DomainException.class, () -> service.calculate(user, drafts.quoteById(user, next), 1));
    }

    @Test
    void historyIsSeparatedByAccountAndHttpEnforcesCsrfAndCompanyOwnership() throws Exception {
        send("request-001");
        assertEquals("NOT_STARTED", service.get(new User("another", "CLIENT", "STABLE", "test"), id).get("status"));
        assertThrows(DomainException.class, () -> service.get(new User("gap", "CLIENT", "GAP", "test"), id));
        String path = "/api/v1/drafts/" + id + "/scenario-dialog";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        var login = mvc.perform(post("/api/v1/auth/sessions").contentType("application/json")
            .content("{\"login\":\"stable\",\"password\":\"Alfa2026!\"}")).andReturn().getResponse();
        mvc.perform(get(path).cookie(login.getCookie("cabinet_session"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("QUEUED"));
        mvc.perform(post(path + "/confirm").cookie(login.getCookie("cabinet_session")).contentType("application/json")
            .content("{\"plan_version\":1}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void correctionDoesNotResurrectAnOldProposalWhenTheModelOnlyAsksAQuestion() throws Exception {
        propose();
        service.confirm(user, id, Map.of("plan_version", 1));
        when(model.complete(anyList(), anyList())).thenReturn(answer("Как изменится бюджет?"));
        send("request-correction");
        service.processNext();
        var state = service.get(user, id);
        assertNull(state.get("proposed_plan"));
        assertNotEquals(true, state.get("ready_to_prepare"));
        assertNotNull(state.get("confirmed_plan"));
        assertThrows(DomainException.class, () -> service.confirm(user, id, Map.of("plan_version", 1)));
    }

    @Test
    void earlierUserConstraintsSurviveTheFiveTurnWindow() throws Exception {
        when(model.complete(anyList(), anyList())).thenReturn(answer("Параметры ещё уточняются."));
        service.send(user, id,
            Map.of("message", "Аренда оплачивается и до открытия. Нельзя использовать оборотные деньги.",
                "quote_id", qid, "request_id", "request-history-0"));
        service.processNext();
        for (int i = 1; i < 7; i++) {
            send("request-history-" + i);
            service.processNext();
        }
        @SuppressWarnings("unchecked")
        var prompts = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(model, times(7)).complete(prompts.capture(), anyList());
        assertTrue(json.write(Map.of("messages", prompts.getValue())).contains("Нельзя использовать оборотные деньги"));
    }

    @Test
    void newToolCallsCannotBypassTimingAndSourcesUsingALegacyPlan() throws Exception {
        when(model.complete(anyList(), anyList())).thenReturn(
            call("simulate_scenarios", Map.of("plan", BusinessPlanTest.plan())),
            answer("Нужны сроки оплат и источники."));
        send("request-legacy-plan");
        service.processNext();
        service.processNext();
        assertNull(service.get(user, id).get("proposed_plan"));
        var stored = db.queryForObject("SELECT history_json FROM business_scenario_dialogs WHERE draft_id=?",
            String.class, id);
        assertTrue(stored.contains("schema_version=2"));
    }

    static Map<String, Object> sparsePlan() {
        var plan = new java.util.HashMap<String, Object>();
        plan.put("schema_version", 2);
        plan.put("title", "Покупка оборудования");
        for (String key : List.of("investment", "launch_months", "cashflow", "investment_schedule", "sources"))
            plan.put(key, null);
        plan.put("components", List.of());
        plan.put("assumptions", List.of());
        return plan;
    }

    @Test
    void equipmentGoalWithNoNumbersSavesPartialPlanAndAsksQuestionsWithToolsDisabled() throws Exception {
        when(model.complete(anyList(), anyList())).thenReturn(
            call("get_missing_inputs", Map.of("plan", sparsePlan())),
            answer("Какое оборудование хотите купить? Сколько оно стоит с установкой? Когда планируете запуск?"));
        service.send(user, id,
            Map.of("message", "хочу купить оборудование", "quote_id", qid, "request_id", "request-equipment"));
        service.processNext();
        assertEquals("QUEUED", service.get(user, id).get("status"));
        assertEquals(1, BusinessPlanTest.rows(service.get(user, id), "messages").size());
        assertNull(service.get(user, id).get("proposed_plan"));
        service.processNext();
        var state = service.get(user, id);
        assertEquals("IDLE", state.get("status"));
        assertTrue(BusinessPlanTest.rows(state, "messages").getLast().get("content").toString()
            .contains("Какое оборудование"));
        verify(model).complete(anyList(), eq(List.of()));
    }

    @Test
    void repeatedContextCallsStopAfterTwoStepsWithConcreteQuestions() throws Exception {
        when(model.complete(anyList(), anyList())).thenReturn(call("get_business_context", Map.of()));
        service.send(user, id,
            Map.of("message", "хочу купить оборудование", "quote_id", qid, "request_id", "request-loop"));
        for (int i = 0; i < 6; i++)
            service.processNext();
        var state = service.get(user, id);
        assertEquals("IDLE", state.get("status"));
        var last = BusinessPlanTest.rows(state, "messages").getLast().get("content").toString();
        assertTrue(last.contains("Какое оборудование"));
        assertFalse(last.contains("лимит"));
        verify(model, times(2)).complete(anyList(), anyList());
    }

    @Test
    void ignoredFinalOnlyModeCannotRepeatSimulationOrCreateAProposalFromMissingInputs() throws Exception {
        when(model.complete(anyList(), anyList())).thenReturn(call("get_missing_inputs", Map.of("plan", sparsePlan())));
        send("request-ignore-final");
        service.processNext();
        service.processNext();
        var state = service.get(user, id);
        assertEquals("IDLE", state.get("status"));
        assertNull(state.get("proposed_plan"));
        assertTrue(BusinessPlanTest.rows(state, "messages").getLast().get("content").toString().contains("?"));
        verify(model, times(2)).complete(anyList(), anyList());
    }

    @Test
    void falseReadinessAndToolCommentaryNeverReachTheVisibleConversation() throws Exception {
        var response = call("get_missing_inputs", Map.of("plan", sparsePlan()));
        var message = new java.util.HashMap<>((Map<String, Object>) response.get("message"));
        message.put("content", "Расчёт готов.");
        when(model.complete(anyList(), anyList())).thenReturn(Map.of("message", message), answer("Расчёт готов."));
        send("request-false-ready");
        service.processNext();
        service.processNext();
        var state = service.get(user, id);
        var visible = json.write(Map.of("messages", state.get("messages")));
        assertFalse(visible.contains("Расчёт готов"));
        assertTrue(visible.contains("?"));
        assertEquals("IDLE", state.get("status"));
    }

    @Test
    void malformedProviderMessageProducesAUsefulReplyWithoutExecutingTools() throws Exception {
        when(model.complete(anyList(), anyList()))
            .thenReturn(Map.of("message", Map.of("role", "assistant", "tool_calls", "bad")));
        send("request-malformed");
        service.processNext();
        assertEquals("IDLE", service.get(user, id).get("status"));
        assertNull(service.get(user, id).get("proposed_plan"));
        assertTrue(
            BusinessPlanTest.rows(service.get(user, id), "messages").getLast().get("content").toString().contains("?"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"хочу купить оборудование", "хочу открыть вторую точку",
            "хочу нанять сотрудника"})
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "SCENARIO_LIVE_URL", matches = ".+")
    void liveSparseGoalsProduceUsefulQuestionsWithoutInventingAPlan(String goal) throws Exception {
        var live = new org.alfahack.features.scenarios.adapter.out.http.HttpScenarioModel(
            System.getenv("SCENARIO_LIVE_URL"), 70, json);
        var trace = new java.util.ArrayList<Map<String, Object>>();
        when(model.complete(anyList(), anyList())).thenAnswer(invocation -> {
            List<Map<String, Object>> messages = invocation.getArgument(0);
            List<Map<String, Object>> tools = invocation.getArgument(1);
            var response = live.complete(messages, tools);
            trace.add(Map.of("tools_enabled", !tools.isEmpty(), "response", response));
            return response;
        });
        service.send(user, id, Map.of("message", goal, "quote_id", qid, "request_id", "request-live-goal"));
        try {
            for (int i = 0; i < 4 && "QUEUED".equals(service.get(user, id).get("status")); i++)
                service.processNext();
            var state = service.get(user, id);
            assertEquals("IDLE", state.get("status"));
            assertNull(state.get("proposed_plan"));
            assertNull(state.get("confirmed_plan"));
            var history = json.read(db.queryForObject(
                "SELECT history_json FROM business_scenario_dialogs WHERE draft_id=?", String.class, id));
            assertFalse(
                BusinessPlanTest.rows(history, "messages").stream()
                    .anyMatch(m -> "BACKEND".equals(m.get("response_origin"))),
                "Live model needed a backend fallback; inspect target/scenario-live-*.json");
            String answer = BusinessPlanTest.rows(state, "messages").getLast().get("content").toString();
            assertTrue(answer.contains("?") || answer.toLowerCase(java.util.Locale.ROOT).contains("уточните"));
            assertFalse(answer.contains("лимит"));
            assertTrue(trace.size() <= 3);
        } finally {
            java.nio.file.Files.writeString(java.nio.file.Path.of("target/scenario-live-" + id + ".json"),
                json.write(Map.of("goal", goal, "trace", trace, "dialog", service.get(user, id))));
        }
    }
}
