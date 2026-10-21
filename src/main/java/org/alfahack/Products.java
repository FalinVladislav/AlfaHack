package org.alfahack;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class Products {
    public record Product(String id,String name,String purpose,BigDecimal min,BigDecimal max,int minTerm,int maxTerm,BigDecimal rate,String unit) {}
    public static final List<Product> CATALOG=List.of(
        new Product("DEVELOPMENT","Развитие бизнеса","EXPANSION",new BigDecimal("100000"),new BigDecimal("5000000"),12,60,new BigDecimal("0.20"),"MONTHS"),
        new Product("DEVELOPMENT_RATE","Развитие · сниженная ставка","EXPANSION",new BigDecimal("500000"),new BigDecimal("3000000"),24,48,new BigDecimal("0.185"),"MONTHS"),
        new Product("DEVELOPMENT_FLEX","Развитие · увеличенный лимит","EXPANSION",new BigDecimal("1000000"),new BigDecimal("8000000"),12,60,new BigDecimal("0.235"),"MONTHS"),
        new Product("WORKING_CAPITAL","Оборотные средства","WORKING_CAPITAL",new BigDecimal("50000"),new BigDecimal("1000000"),3,12,new BigDecimal("0.22"),"MONTHS"),
        new Product("OVERDRAFT","Краткосрочный разрыв","CASH_GAP",new BigDecimal("10000"),new BigDecimal("300000"),1,60,new BigDecimal("0.24"),"DAYS")
    );
    public List<Map<String,Object>> list() { return CATALOG.stream().map(p->Map.<String,Object>of("id",p.id,"name",p.name,"purpose",p.purpose,"min_amount",p.min,"max_amount",p.max,"min_term",p.minTerm,"max_term",p.maxTerm,"term_unit",p.unit,"annual_rate",p.rate,"policy_version","demo-v1")).toList(); }
    public Product get(String id) { return CATALOG.stream().filter(p->p.id.equals(id)).findFirst().orElseThrow(()->ApiException.bad("UNKNOWN_PRODUCT","Неизвестный продукт")); }
    public String reason(Product p,String purpose,BigDecimal amount,Integer months,Integer days) {
        if (!p.purpose.equals(purpose)) return "PURPOSE_MISMATCH";
        if (amount==null || amount.compareTo(p.min)<0 || amount.compareTo(p.max)>0) return "AMOUNT_OUT_OF_RANGE";
        Integer term=p.unit.equals("MONTHS")?months:days;
        if (term==null || term<p.minTerm || term>p.maxTerm) return "TERM_OUT_OF_RANGE";
        return null;
    }
    public Map<String,Object> calculate(Product p,BigDecimal amount,Integer months,Integer days) {
        if (p.unit.equals("DAYS")) {
            BigDecimal interest=amount.multiply(p.rate).multiply(BigDecimal.valueOf(days)).divide(BigDecimal.valueOf(365),2,RoundingMode.HALF_UP);
            return Map.of("total_repay",amount.add(interest),"interest",interest,"monthly_payment",BigDecimal.ZERO.setScale(2),"payment_count",1,"schedule",List.of(Map.of("period",days,"principal",amount,"interest",interest,"payment",amount.add(interest),"remaining",BigDecimal.ZERO.setScale(2))));
        }
        BigDecimal monthlyRate=p.rate.divide(BigDecimal.valueOf(12),MathContext.DECIMAL128);
        BigDecimal factor=BigDecimal.ONE.add(monthlyRate).pow(months,MathContext.DECIMAL128);
        BigDecimal payment=amount.multiply(monthlyRate).multiply(factor).divide(factor.subtract(BigDecimal.ONE),2,RoundingMode.HALF_UP);
        BigDecimal balance=amount, total=BigDecimal.ZERO;List<Map<String,Object>> schedule=new ArrayList<>();
        for(int i=0;i<months;i++) {
            BigDecimal interest=balance.multiply(monthlyRate).setScale(2,RoundingMode.HALF_UP);
            BigDecimal due=i==months-1?balance.add(interest):payment;
            BigDecimal principal=due.subtract(interest);balance=balance.subtract(principal); total=total.add(due);
            schedule.add(Map.of("period",i+1,"principal",principal,"interest",interest,"payment",due,"remaining",balance));
        }
        return Map.of("total_repay",total,"interest",total.subtract(amount),"monthly_payment",payment,"payment_count",months,"schedule",schedule);
    }
}
