package org.alfahack.features.auth.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.alfahack.core.domain.Session;
import org.alfahack.core.domain.User;
import org.alfahack.core.domain.Util;
import org.alfahack.core.errors.DomainException;
import org.alfahack.features.auth.port.AuthRepository;

public class AuthService implements org.alfahack.features.auth.port.AuthUseCases {

    private final AuthRepository repository;

    public AuthService(
        AuthRepository repository
    ) {
        this.repository = repository;
    }

    public Session login(
        String key,
        String password
    ) {
        var account = repository.account(key);
        var credential = repository.credential(key);
        if (account == null || credential == null || password == null || !MessageDigest.isEqual(
            hashPassword(password, (String) credential.get("salt")).getBytes(StandardCharsets.US_ASCII),
            ((String) credential.get("password_hash")).getBytes(StandardCharsets.US_ASCII)))
            throw DomainException.unauthorized("INVALID_CREDENTIALS", "Неверный логин или пароль");
        String token = Util.hash(Util.id() + Util.id()), csrf = Util.hash(Util.id());
        repository.createSession(token, key, csrf, LocalDateTime.now(ZoneOffset.UTC));
        return new Session(token, Map.of("account_key", key, "role", account.get("role"), "csrf", csrf));
    }

    public void ensureCredential(
        String key,
        String password
    ) {
        if (repository.credential(key) != null)
            return;
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String hex = HexFormat.of().formatHex(salt);
        repository.saveCredential(key, hex, hashPassword(password, hex));
    }

    public User authenticate(
        String token
    ) {
        var row = repository.session(token, LocalDateTime.now(ZoneOffset.UTC).minusHours(24));
        if (row == null)
            throw DomainException.unauthorized("UNAUTHORIZED", "Сначала войдите в кабинет");
        return new User((String) row.get("account_key"), (String) row.get("role"), (String) row.get("company_id"),
            (String) row.get("csrf"));
    }

    public void logout(
        String token
    ) {
        repository.deleteSession(token);
    }

    public Map<String, Object> me(
        User user
    ) {
        var row = repository.account(user.key());
        return Map.of("account_key", user.key(), "role", user.role(), "company_id",
            user.company() == null ? "" : user.company(), "label", row.get("label"), "csrf", user.csrf());
    }

    private static String hashPassword(
        String password,
        String salt
    ) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), HexFormat.of().parseHex(salt), 120_000, 256);
            try {
                return HexFormat.of()
                    .formatHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
            } finally {
                spec.clearPassword();
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Credential hashing failed", ex);
        }
    }
}
