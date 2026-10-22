package org.alfahack;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.Map;
import org.alfahack.core.domain.User;
import org.alfahack.core.errors.DomainException;
import org.alfahack.core.serialization.JsonCodec;
import org.alfahack.features.drafts.port.DraftUseCases;
import org.alfahack.features.goals.port.GoalUseCases;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:goals;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa", "spring.datasource.password=", "app.model-api.url=",
        "app.insights.worker-enabled=false"})
@AutoConfigureMockMvc
@Transactional
class GoalsIntegrationTest {

    @Autowired
    GoalUseCases goals;
    @Autowired
    DraftUseCases drafts;
    @Autowired
    MockMvc mvc;
    @Autowired
    JsonCodec json;
    private final User user = new User("stable", "CLIENT", "STABLE", "test");

    @Test
    void confirmedGoalIsStoredAndSurvivesParameterChanges() {
        var selection = goals.resolve(user, Map.of("goal_id", "STOCK"));
        var draft = drafts.create(user);
        String id = (String) draft.get("id");
        draft = drafts.update(user, id,
            Map.of("expected_version", draft.get("version"), "purpose", selection.get("purpose"),
                "goal_id", selection.get("goal_id"), "goal_text", selection.get("goal_text"), "amount", "200000",
                "term_months", 6));
        assertEquals("STOCK", draft.get("goal_id"));
        assertEquals("Закупить товары или сырьё", draft.get("goal_text"));
        draft = drafts.update(user, id, Map.of("expected_version", draft.get("version"), "amount", "300000"));
        assertEquals("STOCK", drafts.get(user, id).get("goal_id"));
        draft = drafts.update(user, id, Map.of("expected_version", draft.get("version"), "purpose", "EXPANSION"));
        assertNull(draft.get("goal_id"));
        assertNull(draft.get("goal_text"));
    }

    @Test
    void freeTextIsStoredUnderConfirmedPurposeAndInvalidPresetCannotBeSaved() {
        var draft = drafts.create(user);
        String id = (String) draft.get("id");
        assertThrows(DomainException.class, () -> drafts.update(user, id, Map.of("expected_version", 1,
            "purpose", "CASH_GAP", "goal_id", "EQUIPMENT", "goal_text", "Купить оборудование")));
        draft = drafts.update(user, id, Map.of("expected_version", 1, "purpose", "CASH_GAP", "duration_days", 14,
            "goal_id", "OTHER", "goal_text", "Аренду нужно оплатить до поступления оплаты от клиента"));
        assertEquals("OTHER", draft.get("goal_id"));
        assertEquals("CASH_GAP", draft.get("purpose"));
        assertEquals(14, draft.get("duration_days"));
        assertNull(draft.get("term_months"));
    }

    @Test
    void httpUsesSessionCsrfAndReturnsCatalogRouting() throws Exception {
        mvc.perform(get("/api/v1/financing/goals")).andExpect(status().isUnauthorized());
        var response = mvc.perform(post("/api/v1/auth/sessions").contentType("application/json")
            .content("{\"login\":\"stable\",\"password\":\"Alfa2026!\"}"))
            .andExpect(status().isOk()).andReturn().getResponse();
        var cookie = response.getCookie("cabinet_session");
        var csrf = json.read(response.getContentAsString()).get("csrf");
        mvc.perform(get("/api/v1/financing/goals").cookie(cookie)).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(6));
        mvc.perform(post("/api/v1/financing/resolve-goal").cookie(cookie).contentType("application/json")
            .content("{\"goal_id\":\"STOCK\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/financing/resolve-goal").cookie(cookie).header("X-CSRF-Token", csrf)
            .contentType("application/json").content("{\"goal_id\":\"STOCK\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.purpose").value("WORKING_CAPITAL"))
            .andExpect(jsonPath("$.product_ids[0]").value("WORKING_CAPITAL"));
    }
}
