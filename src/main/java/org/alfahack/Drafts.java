package org.alfahack;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Drafts {
    private final JdbcTemplate db; private final Products products; private final LocalDate today;
    public Drafts(JdbcTemplate db,Products products,@Value("${app.demo-today}") String today) { this.db=db; this.products=products; this.today=LocalDate.parse(today); }
    public Map<String,Object> get(AuthService.User user,String id) {
        user.client();
        var rows=db.queryForList("SELECT * FROM drafts WHERE id=? AND company_id=?",id,user.company());
        if(rows.isEmpty()) throw ApiException.missing(); return rows.getFirst();
    }
    public List<Map<String,Object>> list(AuthService.User user) { user.client(); return db.queryForList("SELECT * FROM drafts WHERE company_id=? ORDER BY updated_at DESC",user.company()); }
    @Transactional public Map<String,Object> create(AuthService.User user) {
        user.client();
        String id=Util.id(); db.update("INSERT INTO drafts(id,company_id,version,status,updated_at) VALUES (?,?,1,'ACTIVE',?)",id,user.company(),LocalDateTime.now());
        return get(user,id);
    }
    @Transactional public Map<String,Object> update(AuthService.User user,String id,Map<String,Object> body) {
        Map<String,Object> row=get(user,id);
        if (!"ACTIVE".equals(row.get("status"))) throw ApiException.conflict("DRAFT_SUBMITTED","Черновик уже отправлен");
        int expected=Util.number(body.get("expected_version"),1,Integer.MAX_VALUE,"expected_version");
        if (expected != ((Number)row.get("version")).intValue()) throw ApiException.conflict("DRAFT_VERSION_CONFLICT","Черновик изменён в другой вкладке. Обновите данные");
        String purpose=body.containsKey("purpose")?Util.string(body,"purpose"):(String)row.get("purpose");
        if (purpose!=null && !List.of("EXPANSION","WORKING_CAPITAL","CASH_GAP").contains(purpose)) throw ApiException.bad("INVALID_PURPOSE","Выберите цель финансирования");
        BigDecimal amount=body.containsKey("amount")?Util.money(body.get("amount")):(BigDecimal)row.get("amount");
        Integer months=body.containsKey("term_months")?(body.get("term_months")==null?null:Util.number(body.get("term_months"),1,120,"term_months")):(Integer)row.get("term_months");
        Integer days=body.containsKey("duration_days")?(body.get("duration_days")==null?null:Util.number(body.get("duration_days"),1,365,"duration_days")):(Integer)row.get("duration_days");
        LocalDate date=body.containsKey("needed_by")?(body.get("needed_by")==null?null:Util.date(body.get("needed_by"),"needed_by")):row.get("needed_by")==null?null:((java.sql.Date)row.get("needed_by")).toLocalDate();
        String product=body.containsKey("product_id")?Util.string(body,"product_id"):(String)row.get("product_id");
        if (product!=null && !product.isBlank()) products.get(product);
        if (purpose!=null && purpose.equals("CASH_GAP")) months=null; else days=null;
        int changed=db.update("UPDATE drafts SET version=version+1,purpose=?,amount=?,term_months=?,duration_days=?,needed_by=?,product_id=?,updated_at=? WHERE id=? AND company_id=? AND version=? AND status='ACTIVE'",purpose,amount,months,days,date,product,LocalDateTime.now(),id,user.company(),expected);
        if(changed!=1) throw ApiException.conflict("DRAFT_VERSION_CONFLICT","Черновик изменён. Обновите данные");
        return get(user,id);
    }
    public Map<String,Object> quote(AuthService.User user,String id,Map<String,Object> body) {
        Map<String,Object> d=get(user,id);
        int expected=Util.number(body.get("expected_version"),1,Integer.MAX_VALUE,"expected_version");
        if(expected!=((Number)d.get("version")).intValue()) throw ApiException.conflict("DRAFT_VERSION_CONFLICT","Пересчитайте обновлённый черновик");
        BigDecimal amount=(BigDecimal)d.get("amount"); String purpose=(String)d.get("purpose"); Integer months=(Integer)d.get("term_months"); Integer days=(Integer)d.get("duration_days");
        if(amount==null || purpose==null) throw ApiException.bad("INCOMPLETE_DRAFT","Укажите цель и сумму");
        List<Map<String,Object>> options=new ArrayList<>();
        for(Products.Product p:Products.CATALOG) {
            String reason=products.reason(p,purpose,amount,months,days);
            if(reason==null && d.get("needed_by")!=null && ((java.sql.Date)d.get("needed_by")).toLocalDate().isBefore(today.plusDays(1))) reason="TOO_LATE";
            Map<String,Object> option=new LinkedHashMap<>(); option.put("product_id",p.id()); option.put("name",p.name()); option.put("reason_code",reason); option.put("annual_rate",p.rate());
            if(reason==null) {
                Map<String,Object> calc=products.calculate(p,amount,months,days); String qid=Util.id();
                db.update("INSERT INTO quotes VALUES (?,?,?,?,?,?,?,?,?,?,?)",qid,id,expected,p.id(),amount,months,days,p.rate(),calc.get("monthly_payment"),calc.get("total_repay"),LocalDateTime.now());
                option.put("quote_id",qid); option.putAll(calc);
            }
            options.add(option);
        }
        return Map.of("draft_id",id,"draft_version",expected,"policy_version","demo-v1","options",options);
    }
    public Map<String,Object> quoteById(AuthService.User user,String id) {
        user.client(); var rows=db.queryForList("SELECT q.* FROM quotes q JOIN drafts d ON d.id=q.draft_id WHERE q.id=? AND d.company_id=?",id,user.company());
        if(rows.isEmpty()) throw ApiException.missing(); return rows.getFirst();
    }
}
