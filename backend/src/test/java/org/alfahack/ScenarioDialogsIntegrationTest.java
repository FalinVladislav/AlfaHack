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
        java.nio.file.Files.writeString(java.nio.file.Path.of("../target/scenario-model-fixture.json"),
            json.write(Map.of(
                "tools", tools, "plan", BusinessPlanTest.plan(), "instructions",
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
            call("simulate_scenarios", Map.of("plan", BusinessPlanTest.plan())), answer("Проверьте параметры плана."));
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
        assertEquals("business-dependencies-v1", result.get("model_version"));
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
            .thenReturn(call("get_missing_inputs", Map.of("plan", BusinessPlanTest.plan())), answer("Расчёт готов."));
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
        String plan = json.write(BusinessPlanTest.plan());
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
        var partial = BusinessPlanTest.plan();
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
}
