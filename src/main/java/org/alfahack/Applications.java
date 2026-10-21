package org.alfahack;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class Applications {
    private final JdbcTemplate db; private final Drafts drafts; private final Products products; private final ScoringEngine scoring; private final SyntheticProfiles profiles; private final ObjectMapper json; private final LocalDate today;
    public Applications(JdbcTemplate db,Drafts drafts,Products products,ScoringEngine scoring,SyntheticProfiles profiles,ObjectMapper json,@Value("${app.demo-today}") String today) { this.db=db;this.drafts=drafts;this.products=products;this.scoring=scoring;this.profiles=profiles;this.json=json;this.today=LocalDate.parse(today); }
    public List<Map<String,Object>> list(AuthService.User user) {
        String sql="SELECT id,company_id,status,amount,product_id,created_at FROM applications "+(user.role().equals("CLIENT")?"WHERE company_id=? ":"")+"ORDER BY created_at DESC";
        return user.role().equals("CLIENT")?db.queryForList(sql,user.company()):db.queryForList(sql);
    }
    public Map<String,Object> raw(AuthService.User user,String id) {
        var rows=user.role().equals("CLIENT")?db.queryForList("SELECT * FROM applications WHERE id=? AND company_id=?",id,user.company()):db.queryForList("SELECT * FROM applications WHERE id=?",id);
        if(rows.isEmpty()) throw ApiException.missing(); return rows.getFirst();
    }
    public Map<String,Object> detail(AuthService.User user,String id) {
        Map<String,Object> a=raw(user,id); Map<String,Object> out=new LinkedHashMap<>(a); String status=(String)a.get("status");
        out.put("next_action",switch(status) { case "NEEDS_INFO"->"PROVIDE_INFO"; case "APPROVED","COUNTEROFFER"->"ACCEPT_OFFER"; case "OFFER_ACCEPTED"->"SIGN_CONTRACT"; case "SIGNED"->"DISBURSE"; case "DISBURSED"->"VIEW_LOAN"; default->"NONE"; });
        out.put("requests",db.queryForList("SELECT * FROM info_requests WHERE application_id=?",id));
        out.put("events",db.queryForList("SELECT actor,old_status,new_status,reason_code,created_at FROM application_events WHERE application_id=? ORDER BY created_at",id));
        var loans=db.queryForList("SELECT * FROM loans WHERE application_id=?",id); if(!loans.isEmpty()) out.put("loan",loans.getFirst());
        var reports=db.queryForList("SELECT report_json FROM scoring_reports WHERE application_id=?",id);
        if(!reports.isEmpty())try {out.put("decision_report",json.readValue((String)reports.getFirst().get("report_json"),Map.class));}catch(Exception ex){throw new IllegalStateException(ex);}
        if(user.role().equals("CLIENT")) {out.remove("score");out.remove("demo_profile");}
        return out;
    }
    @Transactional public Map<String,Object> submit(AuthService.User user,Map<String,Object> body,String key) {
        user.client(); String draftId=Util.required(body,"draft_id"), quoteId=Util.required(body,"quote_id"); String bodyHash=Util.hash(draftId+"|"+quoteId+"|"+body.get("expected_version")+"|"+body.get("consents"));
        String old=existing(user,"SUBMIT",key,bodyHash); if(old!=null) return detail(user,old);
        if(!Boolean.TRUE.equals(body.get("consents"))) throw ApiException.bad("CONSENT_REQUIRED","Подтвердите согласие перед отправкой");
        Map<String,Object> d=drafts.get(user,draftId), q=drafts.quoteById(user,quoteId);
        int expected=Util.number(body.get("expected_version"),1,Integer.MAX_VALUE,"expected_version");
        if(!"ACTIVE".equals(d.get("status")) || !draftId.equals(q.get("draft_id")) || expected!=((Number)d.get("version")).intValue() || expected!=((Number)q.get("draft_version")).intValue()) throw ApiException.conflict("QUOTE_STALE","Расчёт устарел. Пересчитайте условия");
        if(d.get("needed_by")==null) throw ApiException.bad("NEEDED_BY_REQUIRED","Укажите дату, к которой нужны деньги");
        int locked=db.update("UPDATE drafts SET status='SUBMITTED',updated_at=? WHERE id=? AND company_id=? AND version=? AND status='ACTIVE'",LocalDateTime.now(),draftId,user.company(),expected);
        if(locked!=1) throw ApiException.conflict("QUOTE_STALE","Черновик изменился. Пересчитайте условия");
        String id=Util.id(); LocalDateTime now=LocalDateTime.now();
        db.update("INSERT INTO applications VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,user.company(),draftId,quoteId,"SUBMITTED",null,q.get("amount"),q.get("term_months"),q.get("duration_days"),q.get("product_id"),q.get("annual_rate"),q.get("monthly_payment"),q.get("total_repay"),null,null,0,1,now,now);
        event(id,user.key(),null,"SUBMITTED",null); storeKey(user,"SUBMIT",key,bodyHash,id);
        return detail(user,id);
    }
    @Transactional public Map<String,Object> check(AuthService.User user,String id,String profile) {
        user.staff(); Map<String,Object> a=raw(user,id); String status=(String)a.get("status");
        if(!List.of("SUBMITTED","CHECKING","NEEDS_INFO").contains(status)) throw ApiException.conflict("INVALID_STATE","Проверки уже завершены");
        if(a.get("demo_profile")==null) {
            if(profile==null || !List.of("GOOD","GAP","MISSING_INFO","COUNTEROFFER","HIGH_RISK","RETAIL","MANUFACTURING","IT","CAFE","LOGISTICS","AGRICULTURE","CONSTRUCTION","MOCK_FAILURE","PRIORITY_CONFLICT").contains(profile)) throw ApiException.bad("PROFILE_REQUIRED","Выберите синтетический профиль");
            db.update("UPDATE applications SET demo_profile=? WHERE id=? AND demo_profile IS NULL",profile,id); a=raw(user,id);
        } else if(profile!=null && !profile.equals(a.get("demo_profile"))) throw ApiException.conflict("PROFILE_LOCKED","Профиль закреплён после запуска проверок");
        if(status.equals("NEEDS_INFO")) {
            int pending=db.queryForObject("SELECT COUNT(*) FROM info_requests WHERE application_id=? AND status<>'VERIFIED'",Integer.class,id);
            if(pending>0) throw ApiException.conflict("INFO_PENDING","Сначала подтвердите запрошенные сведения");
        }
        transition(id,user.key(),status,"CHECKING",null);
        String selected=(String)a.get("demo_profile");
        int verified=db.queryForObject("SELECT COUNT(*) FROM info_requests WHERE application_id=? AND status='VERIFIED'",Integer.class,id);
        if("MISSING_INFO".equals(selected) && verified==0) {
            String requestId=Util.id();db.update("INSERT INTO info_requests VALUES (?,?,?,?,?,?)",requestId,id,"UNKNOWN_DEBT","FINANCIAL_STATEMENT","2026-09","OPEN");
            transition(id,"RULES","CHECKING","NEEDS_INFO","UNKNOWN_DEBT");return detail(user,id);
        }
        BigDecimal amount=(BigDecimal)a.get("amount"); BigDecimal payment=(BigDecimal)a.get("monthly_payment"); String product=(String)a.get("product_id");
        Map<String,Object> report=scoring.assess("MISSING_INFO".equals(selected)?"GOOD":selected,amount,payment==null?BigDecimal.ZERO:payment);
        try {String serialized=json.writeValueAsString(report);db.update("DELETE FROM scoring_reports WHERE application_id=?",id);db.update("INSERT INTO scoring_reports VALUES (?,?)",id,serialized);}catch(Exception ex){throw new IllegalStateException(ex);}
        if("SOURCE_ERROR".equals(report.get("outcome"))){transition(id,"MOCKS","CHECKING","CHECKING","SOURCE_UNAVAILABLE");return detail(user,id);}
        if("INSUFFICIENT_DATA".equals(report.get("outcome"))){db.update("INSERT INTO info_requests VALUES (?,?,?,?,?,?)",Util.id(),id,"INCOME_UNKNOWN","FINANCIAL_STATEMENT","2026-09","OPEN");transition(id,"RULES","CHECKING","NEEDS_INFO","INCOME_UNKNOWN");return detail(user,id);}
        int score=((Number)report.get("adjusted_score")).intValue();db.update("UPDATE applications SET score=? WHERE id=?",score,id);
        if("REJECTED".equals(report.get("outcome"))){transition(id,"RULES","CHECKING","REJECTED",(String)report.get("code"));return detail(user,id);}
        SyntheticProfiles.Profile input=profiles.get(selected);
        BigDecimal income=input.monthlyRevenue(),costs=income.subtract(input.monthlyProfit()),debt=input.monthlyDebt();
        BigDecimal free=income.subtract(costs).subtract(debt);
        if(free.signum()<=0){transition(id,"RULES","CHECKING","REJECTED","NO_FREE_CASHFLOW");return detail(user,id);}
        BigDecimal allowed=free.multiply(new BigDecimal("0.50"));
        if(product.equals("OVERDRAFT")) {
            LocalDate issue=((java.sql.Date)db.queryForMap("SELECT needed_by FROM drafts WHERE id=?",a.get("draft_id")).get("needed_by")).toLocalDate();
            LocalDate due=issue.plusDays(((Number)a.get("duration_days")).longValue());
            BigDecimal starting=(BigDecimal)db.queryForMap("SELECT balance FROM companies WHERE id=?",a.get("company_id")).get("balance");
            BigDecimal planned=(BigDecimal)db.queryForMap("SELECT COALESCE(SUM(amount),0) AS total FROM ledger_events WHERE company_id=? AND event_date>? AND event_date<=? AND kind<>'DISBURSEMENT'",a.get("company_id"),today,due).get("total");
            BigDecimal interest=((BigDecimal)a.get("total_repay")).subtract(amount);
            if(starting.add(planned).subtract(interest).signum()<0){transition(id,"RULES","CHECKING","REJECTED","PAYBACK_SHORTFALL");return detail(user,id);}
            if("CONDITIONAL".equals(report.get("outcome"))) {
                BigDecimal reduced=amount.multiply(new BigDecimal("0.70")).setScale(2,RoundingMode.DOWN);
                Products.Product p=products.get(product);
                if(reduced.compareTo(p.min())<0){transition(id,"RULES","CHECKING","REJECTED","RISK_LIMIT");return detail(user,id);}
                Map<String,Object> calc=products.calculate(p,reduced,null,(Integer)a.get("duration_days"));
                db.update("UPDATE applications SET amount=?,total_repay=?,offer_version=offer_version+1 WHERE id=?",reduced,calc.get("total_repay"),id);
                transition(id,"RULES","CHECKING","COUNTEROFFER","RISK_LIMIT");return detail(user,id);
            }
        }
        if(!product.equals("OVERDRAFT") && (payment.compareTo(allowed)>0 || "CONDITIONAL".equals(report.get("outcome")))) {
            BigDecimal reduced=payment.compareTo(allowed)>0?amount.multiply(allowed).divide(payment,2,RoundingMode.DOWN):amount.multiply(new BigDecimal("0.70")).setScale(2,RoundingMode.DOWN);
            if(reduced.compareTo(amount)>=0)reduced=amount.multiply(new BigDecimal("0.70")).setScale(2,RoundingMode.DOWN);
            if(score<75) reduced=reduced.min(amount.multiply(new BigDecimal("0.5")));
            Products.Product p=products.get(product);
            if(reduced.compareTo(p.min())<0) {transition(id,"RULES","CHECKING","REJECTED","PAYMENT_LIMIT");return detail(user,id);}
            Map<String,Object> calc=products.calculate(p,reduced,(Integer)a.get("term_months"),null);
            db.update("UPDATE applications SET amount=?,monthly_payment=?,total_repay=?,offer_version=offer_version+1 WHERE id=?",reduced,calc.get("monthly_payment"),calc.get("total_repay"),id);
            transition(id,"RULES","CHECKING","COUNTEROFFER",payment.compareTo(allowed)>0?"PAYMENT_LIMIT":"RISK_LIMIT");return detail(user,id);
        }
        transition(id,"RULES","CHECKING","APPROVED","POLICY_PASSED");return detail(user,id);
    }
    @Transactional public Map<String,Object> accept(AuthService.User user,String id,Map<String,Object> body,String key) {
        user.client(); String hash=Util.hash(id+"|"+body); String old=existing(user,"ACCEPT",key,hash); if(old!=null)return detail(user,old);
        Map<String,Object> a=raw(user,id);
        if(!List.of("APPROVED","COUNTEROFFER").contains(a.get("status")))throw ApiException.conflict("INVALID_STATE","Предложение недоступно");
        if(Util.number(body.get("offer_version"),0,Integer.MAX_VALUE,"offer_version")!=((Number)a.get("offer_version")).intValue())throw ApiException.conflict("OFFER_STALE","Условия изменились. Обновите заявку");
        transition(id,user.key(),(String)a.get("status"),"OFFER_ACCEPTED",null);storeKey(user,"ACCEPT",key,hash,id);return detail(user,id);
    }
    @Transactional public Map<String,Object> decline(AuthService.User user,String id) {
        user.client();Map<String,Object> a=raw(user,id);
        if(!List.of("APPROVED","COUNTEROFFER").contains(a.get("status")))throw ApiException.conflict("INVALID_STATE","Предложение недоступно");
        transition(id,user.key(),(String)a.get("status"),"CLOSED","CLIENT_DECLINED");return detail(user,id);
    }
    @Transactional public Map<String,Object> sign(AuthService.User user,String id,Map<String,Object> body,String key) {
        user.client();String hash=Util.hash(id+"|"+body);String old=existing(user,"SIGN",key,hash);if(old!=null)return detail(user,old);
        Map<String,Object> a=raw(user,id);if(!"OFFER_ACCEPTED".equals(a.get("status")))throw ApiException.conflict("INVALID_STATE","Сначала примите предложение");
        if(!Boolean.TRUE.equals(body.get("confirmation")))throw ApiException.bad("CONFIRMATION_REQUIRED","Подтвердите подписание");
        transition(id,user.key(),"OFFER_ACCEPTED","SIGNED",null);storeKey(user,"SIGN",key,hash,id);return detail(user,id);
    }
    @Transactional public Map<String,Object> disburse(AuthService.User user,String id,String key) {
        user.client();String old=existing(user,"DISBURSE",key,Util.hash(id));if(old!=null)return detail(user,old);
        Map<String,Object> a=raw(user,id);if(!"SIGNED".equals(a.get("status")))throw ApiException.conflict("INVALID_STATE","Договор ещё не подписан");
        String loanId=Util.id();BigDecimal amount=(BigDecimal)a.get("amount");BigDecimal next=(BigDecimal)a.get("monthly_payment");
        if("OVERDRAFT".equals(a.get("product_id")))next=(BigDecimal)a.get("total_repay");
        LocalDate due="OVERDRAFT".equals(a.get("product_id"))?today.plusDays(((Number)a.get("duration_days")).longValue()):today.plusMonths(1);
        db.update("INSERT INTO loans VALUES (?,?,?,?,?,?,?,?)",loanId,id,user.company(),amount,amount,due,next,LocalDateTime.now());
        db.update("INSERT INTO ledger_events VALUES (?,?,?,?,?,?)",Util.id(),user.company(),today,amount,"DISBURSEMENT","Выдача кредита "+loanId);
        db.update("UPDATE companies SET balance=balance+? WHERE id=?",amount,user.company());
        transition(id,user.key(),"SIGNED","DISBURSED",null);storeKey(user,"DISBURSE",key,Util.hash(id),id);return detail(user,id);
    }
    public Map<String,Object> contract(AuthService.User user,String id) {
        Map<String,Object> a=raw(user,id);if(!List.of("OFFER_ACCEPTED","SIGNED","DISBURSED").contains(a.get("status")))throw ApiException.conflict("INVALID_STATE","Договор ещё не готов");
        return Map.of("number","CR-"+id.substring(0,8).toUpperCase(),"label","Кредитный договор","company_id",a.get("company_id"),"product_id",a.get("product_id"),"amount",a.get("amount"),"annual_rate",a.get("annual_rate"),"total_repay",a.get("total_repay"),"offer_version",a.get("offer_version"));
    }
    public List<Map<String,Object>> loans(AuthService.User user) {user.client();return db.queryForList("SELECT * FROM loans WHERE company_id=? ORDER BY created_at DESC",user.company());}
    public Map<String,Object> loan(AuthService.User user,String id) {
        user.client();var rows=db.queryForList("SELECT * FROM loans WHERE id=? AND company_id=?",id,user.company());if(rows.isEmpty())throw ApiException.missing();return rows.getFirst();
    }
    private void event(String id,String actor,String old,String next,String reason) {db.update("INSERT INTO application_events VALUES (?,?,?,?,?,?,?)",Util.id(),id,actor,old,next,reason,LocalDateTime.now());}
    private void transition(String id,String actor,String old,String next,String reason) {
        int changed=db.update("UPDATE applications SET status=?,reason_code=?,version=version+1,updated_at=? WHERE id=? AND status=?",next,reason,LocalDateTime.now(),id,old);
        if(changed!=1)throw ApiException.conflict("STATE_CHANGED","Заявка изменилась. Обновите данные");event(id,actor,old,next,reason);
    }
    private String existing(AuthService.User user,String operation,String key,String hash) {
        if(key==null || key.isBlank())throw ApiException.bad("IDEMPOTENCY_KEY_REQUIRED","Повторите запрос с ключом Idempotency-Key");
        var rows=db.queryForList("SELECT body_hash,result_id FROM idempotency_records WHERE account_key=? AND operation=? AND request_key=?",user.key(),operation,key);
        if(rows.isEmpty())return null;
        if(!hash.equals(rows.getFirst().get("body_hash")))throw ApiException.conflict("IDEMPOTENCY_CONFLICT","Этот ключ уже использован для другого запроса");
        return (String)rows.getFirst().get("result_id");
    }
    private void storeKey(AuthService.User user,String operation,String key,String hash,String id) {db.update("INSERT INTO idempotency_records VALUES (?,?,?,?,?)",user.key(),operation,key,hash,id);}
}
