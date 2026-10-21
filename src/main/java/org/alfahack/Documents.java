package org.alfahack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class Documents {
    private final JdbcTemplate db;private final Applications applications;
    public Documents(JdbcTemplate db,Applications applications) {this.db=db;this.applications=applications;}
    @Transactional public Map<String,Object> upload(AuthService.User user,String appId,String requestId,MultipartFile file) throws IOException {
        user.client();Map<String,Object> a=applications.raw(user,appId);
        if(!"NEEDS_INFO".equals(a.get("status")))throw ApiException.conflict("INVALID_STATE","Документ сейчас не запрошен");
        var rows=db.queryForList("SELECT * FROM info_requests WHERE id=? AND application_id=?",requestId,appId);
        if(rows.isEmpty())throw ApiException.missing();Map<String,Object> request=rows.getFirst();
        if(file.isEmpty()||file.getSize()>100_000)throw ApiException.bad("INVALID_FILE","Выберите подготовленную выписку CSV");
        String filename=file.getOriginalFilename()==null?"":file.getOriginalFilename();
        byte[] bytes=file.getBytes();
        if(!filename.toLowerCase().endsWith(".csv") || !new String(bytes,StandardCharsets.UTF_8).matches("(?s)[\\x09\\x0A\\x0D\\x20-\\x7EА-Яа-яЁё]*"))throw ApiException.bad("INVALID_FILE_TYPE","Ожидается CSV с синтетическими данными");
        var resource=new ClassPathResource("seed-documents/"+user.company().toLowerCase()+"_statement.csv");
        if(!resource.exists() || !Util.hash(bytes).equals(Util.hash(resource.getInputStream().readAllBytes())))throw ApiException.bad("SYNTHETIC_ONLY","Файл не соответствует подготовленному синтетическому набору. Он не сохранён");
        String id=Util.id();db.update("INSERT INTO documents VALUES (?,?,?,?,?,?,?,?,?)",id,requestId,user.company(),"financial_statement.csv","text/csv",Util.hash(bytes),"SEED",true,LocalDateTime.now());
        db.update("UPDATE info_requests SET status='VERIFIED' WHERE id=?",requestId);
        return Map.of("id",id,"request_id",requestId,"status","VERIFIED","message","Сведения подтверждены");
    }
    public byte[] download(AuthService.User user,String appId,String id) throws IOException {
        applications.raw(user,appId);
        var rows=db.queryForList("SELECT d.* FROM documents d JOIN info_requests r ON r.id=d.request_id WHERE d.id=? AND r.application_id=?",id,appId);
        if(rows.isEmpty())throw ApiException.missing();Map<String,Object> doc=rows.getFirst();
        if(user.role().equals("CLIENT")&&!user.company().equals(doc.get("company_id")))throw ApiException.missing();
        return new ClassPathResource("seed-documents/"+doc.get("company_id").toString().toLowerCase()+"_statement.csv").getInputStream().readAllBytes();
    }
    public Map<String,Object> complete(AuthService.User user,String appId,String requestId) {
        user.client();applications.raw(user,appId);
        var rows=db.queryForList("SELECT status FROM info_requests WHERE id=? AND application_id=?",requestId,appId);
        if(rows.isEmpty())throw ApiException.missing();
        if(!"VERIFIED".equals(rows.getFirst().get("status")))throw ApiException.conflict("DOCUMENT_NOT_VERIFIED","Документ ещё не подтверждён");
        return applications.check(new AuthService.User("SYSTEM","STAFF",null,""),appId,null);
    }
    public Map<String,Object> info(AuthService.User user,String appId,String id) {
        applications.raw(user,appId);var rows=db.queryForList("SELECT d.id,d.filename,d.mime,d.sha256,d.verified,d.uploaded_at FROM documents d JOIN info_requests r ON r.id=d.request_id WHERE d.id=? AND r.application_id=?",id,appId);
        if(rows.isEmpty())throw ApiException.missing();return rows.getFirst();
    }
}
