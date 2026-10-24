package org.alfahack.features.auth.adapter.out.persistence.jdbc;

import java.time.LocalDateTime;
import java.util.Map;
import org.alfahack.features.auth.port.AuthRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuthRepository implements AuthRepository {

    private final JdbcTemplate db;

    public JdbcAuthRepository(
        JdbcTemplate db
    ) {
        this.db = db;
    }

    public Map<String, Object> account(
        String key
    ) {
        return one("SELECT * FROM demo_accounts WHERE account_key=?", key);
    }

    public Map<String, Object> credential(
        String key
    ) {
        return one("SELECT salt,password_hash FROM account_credentials WHERE account_key=?", key);
    }

    public void saveCredential(
        String key,
        String salt,
        String hash
    ) {
        db.update("INSERT INTO account_credentials VALUES (?,?,?)", key, salt, hash);
    }

    public void createSession(
        String token,
        String key,
        String csrf,
        LocalDateTime createdAt
    ) {
        db.update("INSERT INTO sessions VALUES (?,?,?,?)", token, key, csrf, createdAt);
    }

    public Map<String, Object> session(
        String token,
        LocalDateTime notBefore
    ) {
        return token == null
            ? null
            : one(
                "SELECT a.account_key,a.role,a.company_id,s.csrf FROM sessions s JOIN demo_accounts a ON a.account_key=s.account_key WHERE s.token=? AND s.created_at>?",
                token, notBefore);
    }

    public void deleteSession(
        String token
    ) {
        db.update("DELETE FROM sessions WHERE token=?", token);
    }

    private Map<String, Object> one(
        String sql,
        Object... args
    ) {
        var rows = db.queryForList(sql, args);
        return rows.isEmpty() ? null : rows.getFirst();
    }
}
