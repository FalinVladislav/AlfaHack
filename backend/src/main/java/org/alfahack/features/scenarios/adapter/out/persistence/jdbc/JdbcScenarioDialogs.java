package org.alfahack.features.scenarios.adapter.out.persistence.jdbc;

import static org.alfahack.core.adapter.out.persistence.jdbc.JdbcRows.*;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import org.alfahack.features.scenarios.port.ScenarioDialogRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcScenarioDialogs implements ScenarioDialogRepository {

    private final JdbcTemplate db;
    public JdbcScenarioDialogs(JdbcTemplate db) {
        this.db = db;
    }

    public Map<String, Object> find(
        String draft,
        String account,
        String key
    ) {
        var list = db.queryForList(
            "SELECT * FROM business_scenario_dialogs WHERE draft_id=? AND account_key=? AND goal_key=?", draft, account,
            key);
        return list.isEmpty() ? null : row(list.getFirst());
    }

    public Map<String, Object> ensure(
        String draft,
        String account,
        String company,
        String key
    ) {
        db.queryForMap("SELECT id FROM drafts WHERE id=? FOR UPDATE", draft);
        var found = find(draft, account, key);
        if (found != null)
            return found;
        String id = UUID.randomUUID().toString();
        var now = LocalDateTime.now();
        db.update(
            "INSERT INTO business_scenario_dialogs (id,draft_id,account_key,company_id,goal_key,history_json,context_json,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
            id, draft, account, company, key, "{\"messages\":[]}", "{}", "IDLE", now, now);
        return lock(id);
    }

    public Map<String, Object> lock(String id) {
        return row(db.queryForMap("SELECT * FROM business_scenario_dialogs WHERE id=? FOR UPDATE", id));
    }

    public void save(Map<String, Object> d) {
        db.update("UPDATE business_scenario_dialogs SET working_plan_json=? WHERE id=?", d.get("working_plan_json"),
            d.get("id"));
        db.update(
            "UPDATE business_scenario_dialogs SET version=version+1,plan_version=?,confirmed_version=?,proposed_json=?,confirmed_json=?,history_json=?,context_json=?,status=?,working_quote_id=?,round_count=?,lease_token=?,lease_until=?,last_error=?,updated_at=? WHERE id=?",
            d.get("plan_version"), d.get("confirmed_version"), d.get("proposed_json"), d.get("confirmed_json"),
            d.get("history_json"), d.get("context_json"), d.get("status"), d.get("working_quote_id"),
            d.get("round_count"), d.get("lease_token"), d.get("lease_until"), d.get("last_error"), LocalDateTime.now(),
            d.get("id"));
    }

    public Map<String, Object> claim() {
        var now = LocalDateTime.now();
        var jobs = db.queryForList(
            "SELECT * FROM business_scenario_dialogs WHERE status='QUEUED' OR (status='RUNNING' AND lease_until<?) ORDER BY updated_at LIMIT 1",
            now);
        if (jobs.isEmpty())
            return null;
        var job = row(jobs.getFirst());
        String token = UUID.randomUUID().toString();
        if (db.update(
            "UPDATE business_scenario_dialogs SET status='RUNNING',lease_token=?,lease_until=?,updated_at=?,version=version+1 WHERE id=? AND (status='QUEUED' OR (status='RUNNING' AND lease_until<?))",
            token, now.plusMinutes(3), now, job.get("id"), now) != 1)
            return null;
        job.put("lease_token", token);
        job.put("status", "RUNNING");
        return job;
    }

    public void recordRun(
        String dialog,
        String quote,
        int version,
        String context,
        String result
    ) {
        db.update("INSERT INTO business_scenario_runs VALUES (?,?,?,?,?,?,?)", UUID.randomUUID().toString(), dialog,
            quote, version, context, result, LocalDateTime.now());
    }
}
