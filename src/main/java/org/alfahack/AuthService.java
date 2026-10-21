package org.alfahack;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDateTime;
import java.util.Map;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final JdbcTemplate db;
    private final boolean secureCookie;
    public AuthService(JdbcTemplate db, @Value("${app.secure-cookie}") boolean secureCookie) { this.db=db; this.secureCookie=secureCookie; }
    public record User(String key, String role, String company, String csrf) {
        public void client() { if (!role.equals("CLIENT")) throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","Действие доступно клиенту"); }
        public void staff() { if (!role.equals("STAFF")) throw new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","Действие доступно сотруднику"); }
    }
    public Map<String,Object> login(String key, String password, HttpServletResponse response) {
        Map<String,Object> account = one("SELECT * FROM demo_accounts WHERE account_key=?",key);
        Map<String,Object> credential=one("SELECT salt,password_hash FROM account_credentials WHERE account_key=?",key);
        if (account == null || credential == null || password == null || !java.security.MessageDigest.isEqual(hashPassword(password,(String)credential.get("salt")).getBytes(java.nio.charset.StandardCharsets.US_ASCII),((String)credential.get("password_hash")).getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
            throw new ApiException(HttpStatus.UNAUTHORIZED,"INVALID_CREDENTIALS","Неверный логин или пароль");
        String token=Util.hash(Util.id()+Util.id()); String csrf=Util.hash(Util.id());
        db.update("INSERT INTO sessions VALUES (?,?,?,?)",token,key,csrf,LocalDateTime.now());
        Cookie cookie=new Cookie("cabinet_session",token); cookie.setHttpOnly(true); cookie.setPath("/"); cookie.setMaxAge(60*60*24); cookie.setSecure(secureCookie); cookie.setAttribute("SameSite","Lax"); response.addCookie(cookie);
        return Map.of("account_key",key,"role",account.get("role"),"csrf",csrf);
    }
    public void ensureCredential(String key,String password) {
        if (one("SELECT account_key FROM account_credentials WHERE account_key=?",key)!=null) return;
        byte[] salt=new byte[16];new SecureRandom().nextBytes(salt);String hex=HexFormat.of().formatHex(salt);
        db.update("INSERT INTO account_credentials VALUES (?,?,?)",key,hex,hashPassword(password,hex));
    }
    private static String hashPassword(String password,String salt) {
        try {PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),HexFormat.of().parseHex(salt),120_000,256);
            try{return HexFormat.of().formatHex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());}finally{spec.clearPassword();}
        } catch(Exception e){throw new IllegalStateException("Credential hashing failed",e);}
    }
    public void logout(User user, HttpServletRequest request, HttpServletResponse response) {
        db.update("DELETE FROM sessions WHERE token=?",cookie(request));
        Cookie expired=new Cookie("cabinet_session",""); expired.setPath("/"); expired.setMaxAge(0); expired.setHttpOnly(true); expired.setSecure(secureCookie); response.addCookie(expired);
    }
    public User user(HttpServletRequest request) {
        String token=cookie(request);
        Map<String,Object> row=token==null?null:one("SELECT a.account_key,a.role,a.company_id,s.csrf FROM sessions s JOIN demo_accounts a ON a.account_key=s.account_key WHERE s.token=?",token);
        if (row==null) throw new ApiException(HttpStatus.UNAUTHORIZED,"UNAUTHORIZED","Сначала войдите в кабинет");
        if (!request.getMethod().equals("GET") && !request.getMethod().equals("HEAD") && !request.getMethod().equals("OPTIONS")) {
            if (!row.get("csrf").equals(request.getHeader("X-CSRF-Token"))) throw new ApiException(HttpStatus.FORBIDDEN,"CSRF","Обновите страницу и повторите действие");
        }
        return new User((String)row.get("account_key"),(String)row.get("role"),(String)row.get("company_id"),(String)row.get("csrf"));
    }
    public Map<String,Object> me(User user) {
        Map<String,Object> row=one("SELECT label FROM demo_accounts WHERE account_key=?",user.key());
        return Map.of("account_key",user.key(),"role",user.role(),"company_id",user.company()==null?"":user.company(),"label",row.get("label"),"csrf",user.csrf());
    }
    private String cookie(HttpServletRequest request) {
        if (request.getCookies()==null) return null;
        for (Cookie c:request.getCookies()) if (c.getName().equals("cabinet_session")) return c.getValue();
        return null;
    }
    private Map<String,Object> one(String sql,Object... args) {
        var rows=db.queryForList(sql,args); return rows.isEmpty()?null:rows.getFirst();
    }
}
