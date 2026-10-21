package org.alfahack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class BusinessInsights {
    private final JdbcTemplate db;private final SyntheticProfiles profiles;private final LocalDate today;
    public BusinessInsights(JdbcTemplate db,SyntheticProfiles profiles,@Value("${app.demo-today}") String today){this.db=db;this.profiles=profiles;this.today=LocalDate.parse(today);}
    private SyntheticProfiles.Profile profile(AuthService.User user){user.client();return profiles.get(user.company().equals("GAP")?"MANUFACTURING":"CAFE");}
    public Map<String,Object> cashflow(AuthService.User user){
        SyntheticProfiles.Profile p=profile(user);List<Map<String,Object>> months=new ArrayList<>();
        BigDecimal totalIncome=BigDecimal.ZERO,totalExpenses=BigDecimal.ZERO;
        for(int i=6;i>=1;i--){YearMonth month=YearMonth.from(today).minusMonths(i);double factor=1+(6-i)*.025+(i%3-1)*.018;
            BigDecimal income=p.monthlyRevenue().multiply(BigDecimal.valueOf(factor)).setScale(2,RoundingMode.HALF_UP);
            BigDecimal expenses=income.subtract(p.monthlyProfit()).add(p.monthlyDebt()).max(BigDecimal.ZERO);
            months.add(Map.of("month",month.toString(),"income",income,"expenses",expenses,"net",income.subtract(expenses)));
            totalIncome=totalIncome.add(income);totalExpenses=totalExpenses.add(expenses);
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("period","Последние 6 завершённых месяцев");result.put("months",months);result.put("total_income",totalIncome);result.put("total_expenses",totalExpenses);result.put("net",totalIncome.subtract(totalExpenses));
        result.put("expense_categories",List.of(Map.of("name","Поставщики и материалы","share",48),Map.of("name","Команда","share",28),Map.of("name","Аренда и логистика","share",16),Map.of("name","Прочее","share",8)));
        result.put("data_origin","SYNTHETIC");return result;
    }
    public Map<String,Object> insights(AuthService.User user){
        SyntheticProfiles.Profile p=profile(user);List<Map<String,Object>> cards=new ArrayList<>();
        if(user.company().equals("GAP")){
            cards.add(card("Закупка сырья заранее","Если цена материалов снизится, запас можно пополнить до следующего производственного цикла.","Оборотные средства","Сценарий · снижение закупочной цены", "WORKING_CAPITAL",800000,12,"mint"));
            cards.add(card("Закрыть разрыв по платежам","По плановым событиям возможен отрицательный остаток. Краткосрочный лимит поможет провести оплату поставщику вовремя.","До 300 000 ₽","План движения средств", "CASH_GAP",120000,45,"violet"));
            cards.add(card("Новое оборудование","Дополнительная линия увеличит мощность при сохранении текущей загрузки.","Расширение выпуска","Сценарий роста", "EXPANSION",2500000,36,"peach"));
            cards.add(card("Расширение географии продаж","При устойчивом спросе новый регион может увеличить объём заказов после проверки долговой нагрузки.","Новый рынок","Сценарий расширения", "EXPANSION",3000000,36,"blue"));
        } else {
            cards.add(card("Закупка товара с высоким спросом","Финансирование поможет увеличить запас и избежать потери продаж в сезон.","Оборот до +20%","Сценарий спроса", "WORKING_CAPITAL",650000,12,"mint"));
            cards.add(card("Дополнительное оборудование","Новая линия приготовления ускорит обработку заказов в пиковые часы.","Потенциал +12–18%","Сценарий загрузки", "EXPANSION",1800000,36,"peach"));
            cards.add(card("Расширение географии продаж","Финансирование может поддержать открытие новой точки после проверки планового платежа.","Новые точки продаж","Сценарий расширения", "EXPANSION",3000000,36,"blue"));
            cards.add(card("Краткосрочный резерв","Дополнительный лимит поможет сгладить неравномерные поступления и сохранить график оплат.","Резерв ликвидности","Денежный план", "CASH_GAP",150000,30,"violet"));
        }
        return Map.of("company_sector",p.sector(),"basis","Сценарии на синтетических данных; внешние рыночные цены не запрашиваются","cards",cards,"assistant_connection","OLLAMA_OPTIONAL");
    }
    private Map<String,Object> card(String title,String text,String highlight,String signal,String purpose,long amount,int term,String tone){return Map.of("title",title,"text",text,"highlight",highlight,"signal",signal,"purpose",purpose,"amount",amount,"term_months",term,"tone",tone);}
}
