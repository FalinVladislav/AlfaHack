package org.alfahack;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class Forecast {
    private final JdbcTemplate db;private final LocalDate today;
    public Forecast(JdbcTemplate db,@Value("${app.demo-today}") String today){this.db=db;this.today=LocalDate.parse(today);}
    public Map<String,Object> dashboard(AuthService.User user){
        user.client();Map<String,Object> company=db.queryForMap("SELECT * FROM companies WHERE id=?",user.company());
        Map<String,Object> forecast=forecast(user,"30D","BASE");
        return Map.of("company",company,"demo_today",today,"forecast",forecast,"news",List.of(Map.of("title","Как выбрать срок финансирования","text","Сравните платёж и общую стоимость для разных сроков."),Map.of("title","Что происходит после подачи заявки","text","Банк проверяет сведения и при необходимости запрашивает документы.")));
    }
    public Map<String,Object> forecast(AuthService.User user,String horizon,String scenario){
        user.client();if(!List.of("30D","6M").contains(horizon)||!List.of("BASE","STRESS").contains(scenario))throw ApiException.bad("INVALID_SCENARIO","Выберите доступный горизонт и сценарий");
        int days=horizon.equals("30D")?30:183;BigDecimal balance=(BigDecimal)db.queryForMap("SELECT balance FROM companies WHERE id=?",user.company()).get("balance");
        var events=db.queryForList("SELECT event_date,amount,kind,source FROM ledger_events WHERE company_id=? AND event_date>? AND event_date<=? AND kind<>'DISBURSEMENT' ORDER BY event_date",user.company(),today,today.plusDays(days));
        List<Map<String,Object>> points=new ArrayList<>();LocalDate firstNegative=null,minDate=null;BigDecimal min=balance;
        for(int day=1;day<=days;day++){
            LocalDate date=today.plusDays(day);for(var event:events)if(((java.sql.Date)event.get("event_date")).toLocalDate().equals(date))balance=balance.add((BigDecimal)event.get("amount"));
            if(balance.signum()<0&&firstNegative==null)firstNegative=date;if(balance.compareTo(min)<0){min=balance;minDate=date;}
            if(horizon.equals("30D")||date.getDayOfMonth()==date.lengthOfMonth()||day==days)points.add(Map.of("date",date,"balance",balance));
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("status","PLAN_ONLY");result.put("model_version","known-events-v1");result.put("scenario",scenario);result.put("horizon",horizon);result.put("points",points);result.put("first_negative_date",firstNegative);result.put("minimum_date",minDate);result.put("minimum_balance",min);result.put("gap_amount",min.signum()<0?min.negate():BigDecimal.ZERO.setScale(2));result.put("events",events);result.put("assumptions",scenario.equals("STRESS")?"В данных нет оценочных операций; стрессовый ряд совпадает с базовым. Учтены только известные плановые события.":"Учтены только известные плановые события; будущая нерегулярная выручка не прогнозируется.");return result;
    }
    public Map<String,Object> analysis(AuthService.User user,Map<String,Object> quote,String horizon,String scenario) {
        Map<String,Object> baseline=forecast(user,horizon,scenario);
        var draft=db.queryForMap("SELECT purpose,needed_by,version FROM drafts WHERE id=?",quote.get("draft_id"));
        if(((Number)draft.get("version")).intValue()!=((Number)quote.get("draft_version")).intValue()) throw ApiException.conflict("QUOTE_STALE","Расчёт устарел. Сначала обновите варианты");
        String purpose=(String)draft.get("purpose");BigDecimal amount=(BigDecimal)quote.get("amount");
        LocalDate issue=draft.get("needed_by")==null?today.plusDays(1):((java.sql.Date)draft.get("needed_by")).toLocalDate();int days=horizon.equals("30D")?30:183;
        List<Map<String,Object>> noCredit=new ArrayList<>(),withCredit=new ArrayList<>();
        BigDecimal balance=(BigDecimal)db.queryForMap("SELECT balance FROM companies WHERE id=?",user.company()).get("balance");
        var events=db.queryForList("SELECT event_date,amount FROM ledger_events WHERE company_id=? AND event_date>? AND event_date<=? AND kind<>'DISBURSEMENT'",user.company(),today,today.plusDays(days));
        BigDecimal delta=BigDecimal.ZERO;
        for(int day=1;day<=days;day++) {
            LocalDate date=today.plusDays(day);
            for(var event:events)if(((java.sql.Date)event.get("event_date")).toLocalDate().equals(date))balance=balance.add((BigDecimal)event.get("amount"));
            if(date.equals(issue)) {delta=delta.add(amount);if(!purpose.equals("CASH_GAP"))delta=delta.subtract(amount);}
            if(quote.get("duration_days")!=null) {
                if(date.equals(issue.plusDays(((Number)quote.get("duration_days")).longValue())))delta=delta.subtract((BigDecimal)quote.get("total_repay"));
            } else {
                int months=((Number)quote.get("term_months")).intValue();
                for(int m=1;m<=months;m++)if(date.equals(issue.plusMonths(m))) {
                    BigDecimal payment=(BigDecimal)quote.get("monthly_payment");
                    if(m==months)payment=((BigDecimal)quote.get("total_repay")).subtract(payment.multiply(BigDecimal.valueOf(months-1)));
                    delta=delta.subtract(payment);
                }
            }
            if(horizon.equals("30D")||date.getDayOfMonth()==date.lengthOfMonth()||day==days){noCredit.add(Map.of("date",date,"balance",balance));withCredit.add(Map.of("date",date,"balance",balance.add(delta)));}
        }
        return Map.of("status","PLAN_ONLY","horizon",horizon,"scenario",scenario,"without_credit",noCredit,"with_credit",withCredit,"loan_total_repay",quote.get("total_repay"),"assumptions",purpose.equals("CASH_GAP")?"Плановые расходы уже учтены в исходном ряду. Добавлены зачисление и возврат одного транша.":"Вся сумма кредита считается потраченной в день выдачи. Дополнительная выручка не предполагается.","forecast_assumptions",baseline.get("assumptions"));
    }
}
