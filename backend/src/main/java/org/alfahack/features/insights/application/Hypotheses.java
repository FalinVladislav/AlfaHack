package org.alfahack.features.insights.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.alfahack.core.domain.User;
import org.alfahack.core.serialization.JsonCodec;
import org.alfahack.features.finance.port.FinanceUseCases;
import org.alfahack.features.forecast.port.ForecastUseCases;
import org.alfahack.features.insights.port.HypothesisModel;
import org.alfahack.features.insights.port.HypothesisRepository;
import org.alfahack.features.insights.port.HypothesisUseCases;
import org.alfahack.features.products.port.ProductCatalog;

/**
 * Facts and arithmetic are server-owned; the model only explains a grounded
 * candidate.
 */
public class Hypotheses implements HypothesisUseCases {

    private final HypothesisRepository repository;
    private final FinanceUseCases finance;
    private final ForecastUseCases forecast;
    private final ProductCatalog products;
    private final HypothesisModel model;
    private final JsonCodec json;
    private final LocalDate today;

    public Hypotheses(
        HypothesisRepository repository,
        FinanceUseCases finance,
        ForecastUseCases forecast,
        ProductCatalog products,
        HypothesisModel model,
        JsonCodec json,
        String today
    ) {
        this.repository = repository;
        this.finance = finance;
        this.forecast = forecast;
        this.products = products;
        this.model = model;
        this.json = json;
        this.today = LocalDate.parse(today);
    }

    private Map<String, Object> context(User user) {
        user.client();
        var overview = finance.overview(user);
        var result = new LinkedHashMap<String, Object>();
        result.put("version", "grounded-hypotheses-v2");
        result.put("as_of", today);
        result.put("data_origin", overview.get("data_origin"));
        result.put("business", overview.get("company"));
        result.put("months", overview.get("months"));
        result.put("loans", overview.get("loans"));
        result.put("needs", maps(overview.get("needs")).stream().limit(3).toList());
        result.put("calculations", Map.of("cashflow_30d", forecast.forecast(user, "30D", "BASE"),
            "cashflow_6m", forecast.forecast(user, "6M", "BASE")));
        result.put("products", products.list());
        return result;
    }

    private Object canonical(Object value) {
        if (value instanceof Map<?, ?> fields) {
            var sorted = new TreeMap<String, Object>();
            fields.forEach((
                key,
                item) -> sorted.put(String.valueOf(key), canonical(item)));
            return sorted;
        }
        if (value instanceof List<?> values)
            return values.stream().map(this::canonical).toList();
        return value;
    }

    private String fingerprint(Map<String, Object> context) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(json.write(canonical(context)).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot fingerprint context", ex);
        }
    }

    private Map<String, Object> job(User user) {
        var context = context(user);
        return repository.ensure(user.company(), fingerprint(context), json.write(context));
    }

    public Map<String, Object> list(User user) {
        var job = job(user);
        var cards = repository.cards(user.company(), (String) job.get("id")).stream().map(this::decode).toList();
        return Map.of("job", summary(job), "cards", cards);
    }

    private Map<String, Object> summary(Map<String, Object> job) {
        return Map.of("id", job.get("id"), "status", job.get("status"), "message", job.get("message"),
            "updated_at", job.get("updated_at"), "data_version", job.get("fingerprint"));
    }

    private Map<String, Object> decode(Map<String, Object> row) {
        var result = new LinkedHashMap<>(json.read((String) row.get("payload_json")));
        result.put("id", row.get("id"));
        result.put("created_at", row.get("created_at"));
        return result;
    }

    public Map<String, Object> detail(
        User user,
        String id
    ) {
        user.client();
        var row = repository.card(user.company(), id);
        var result = decode(row);
        result.put("stale", !fingerprint(context(user)).equals(row.get("fingerprint")));
        return result;
    }

    public Map<String, Object> retry(User user) {
        var job = job(user);
        repository.retry(user.company(), (String) job.get("id"));
        return list(user);
    }

    public List<Map<String, Object>> notifications(User user) {
        user.client();
        return repository.notifications(user.company(), user.key()).stream().map(row -> {
            var payload = json.read((String) row.get("payload_json"));
            return Map.<String, Object>of("id", row.get("id"), "title", payload.get("title"),
                "created_at", row.get("created_at"), "read", row.get("is_read"), "hypothesis_id", row.get("id"));
        }).toList();
    }

    public void read(
        User user,
        String id
    ) {
        user.client();
        repository.read(user.company(), user.key(), id);
    }

    public void processNext() {
        var job = repository.claim();
        if (job == null)
            return;
        String id = (String) job.get("id"), lease = (String) job.get("lease_token");
        try {
            var context = json.read((String) job.get("context_json"));
            var candidates = candidates(context);
            int failed = 0;
            for (var candidate : candidates) {
                if (!repository.ownsLease(id, lease))
                    return;
                var card = new LinkedHashMap<>(candidate);
                card.put("explanation_source", "RULES");
                if (model.available()) {
                    try {
                        var input = new LinkedHashMap<>(context);
                        input.put("candidate", candidate);
                        var response = model.explain(input);
                        var hypotheses = maps(response.get("hypotheses"));
                        if (hypotheses.isEmpty())
                            continue;
                        if (hypotheses.size() != 1)
                            throw new IllegalArgumentException("Expected one hypothesis");
                        var explanation = hypotheses.getFirst();
                        card.put("title", "Проверить: " + text(explanation.get("title"), 160));
                        for (String key : List.of("rationale", "experiment", "success_metric"))
                            card.put(key, text(explanation.get(key), 2000));
                        Object questions = response.get("questions");
                        if (!(questions instanceof List<?> values) || values.size() > 3)
                            throw new IllegalArgumentException("Invalid questions");
                        card.put("questions", values.stream().map(q -> text(q, 2000)).toList());
                        card.put("explanation_source", "LLM");
                    } catch (Exception ex) {
                        failed++;
                        card = new LinkedHashMap<>(candidate);
                        card.put("explanation_source", "RULES");
                        card.put("enrichment_status", "UNAVAILABLE");
                    }
                }
                repository.publish(id, lease, (String) candidate.get("source_key"), json.write(card));
            }
            int saved = repository.cards((String) job.get("company_id"), id).size();
            repository.finish(id, lease, failed > 0 ? "PARTIAL" : saved == 0 ? "EMPTY" : "READY",
                failed > 0
                    ? "Гипотезы готовы. Расширенное объяснение временно недоступно."
                    : saved == 0
                        ? "Пока нет обоснованных гипотез. Уточните цели и данные бизнеса."
                        : "Гипотезы готовы");
        } catch (Exception ex) {
            repository.finish(id, lease, "FAILED", "Не удалось обработать данные. Попробуйте обновить гипотезы.");
        }
    }

    private List<Map<String, Object>> candidates(Map<String, Object> context) {
        var result = new ArrayList<Map<String, Object>>();
        var calculations = object(context.get("calculations"));
        var plan = object(calculations.get("cashflow_30d"));
        BigDecimal gap = number(plan.get("gap_amount"));
        if (gap.signum() > 0 && plan.get("first_negative_date") != null) {
            var card = base("cash-gap", "Проверьте план платежей", "CASH_GAP", "CALCULATED");
            card.put("rationale",
                "По известным плановым событиям остаток станет отрицательным " + plan.get("first_negative_date")
                    + ". Максимальный недостаток средств — " + gap.toPlainString() + " ₽.");
            card.put("evidence", List.of(Map.of("source", "cashflow_30d", "facts", plan)));
            card.put("assumptions",
                List.of("Учтены только известные события. Нерегулярные поступления не прогнозируются.",
                    "Сумма рассчитана без дополнительного резерва ликвидности."));
            card.put("experiment",
                "Уточнить даты поступлений и обсудить перенос расходов. Затем сравнить с краткосрочным финансированием.");
            card.put("success_metric",
                "Отсутствие отрицательного остатка и наличие средств на возврат финансирования.");
            card.put("questions", List.of("Все ли ожидаемые поступления и обязательства внесены в календарь?"));
            card.put("amount", gap);
            card.put("duration_days", 30);
            card.put("needed_by", plan.get("first_negative_date"));
            card.put("financing",
                financing(context, "CASH_GAP", gap, 30, String.valueOf(plan.get("first_negative_date"))));
            result.add(card);
        }
        for (var need : maps(context.get("needs"))) {
            String goal = text(need.get("goal"), 200);
            BigDecimal amount = number(need.get("requested_amount"));
            boolean stock = goal.toLowerCase(java.util.Locale.ROOT).contains("закуп");
            String purpose = stock ? "WORKING_CAPITAL" : "EXPANSION";
            int term = stock ? 12 : 36;
            var card = base(String.valueOf(need.get("id")), "Проверьте цель: " + goal, purpose, "NEEDS_DATA");
            card.put("rationale", "В данных бизнеса указана цель «" + goal
                + "». Перед вложением средств нужно проверить спрос и экономику.");
            card.put("evidence", List.of(Map.of("source", "loan_requests", "facts", need)));
            card.put("assumptions",
                List.of("Показатели ожидаемого роста в заявленной цели являются допущениями, а не прогнозом.",
                    "Сумма финансирования взята из потребности пользователя; её достаточность ещё не проверена."));
            card.put("questions", List.of("Чем подтверждается дополнительный спрос?",
                "Каковы дополнительные расходы и маржа?", "Когда вложения начнут приносить поступления?"));
            card.put("experiment",
                stock
                    ? "Проверить продажи небольшой партии до крупной закупки."
                    : "Проверить спрос и загрузку до покупки оборудования или расширения.");
            card.put("success_metric",
                "Положительный дополнительный денежный поток после расходов и обслуживания кредита.");
            card.put("amount", amount);
            card.put("term_months", term);
            card.put("needed_by", today.plusDays(10));
            card.put("financing", financing(context, purpose, amount, term, today.plusDays(10).toString()));
            result.add(card);
        }
        return result;
    }

    private Map<String, Object> base(
        String source,
        String title,
        String purpose,
        String status
    ) {
        var result = new LinkedHashMap<String, Object>();
        result.put("source_key", source);
        result.put("title", title);
        result.put("purpose", purpose);
        result.put("status", status);
        result.put("highlight",
            status.equals("CALCULATED") ? "Расчёт по плану платежей" : "Нужны дополнительные данные");
        result.put("data_origin", "SYNTHETIC");
        result.put("as_of", today);
        return result;
    }

    private Map<String, Object> financing(
        Map<String, Object> context,
        String purpose,
        BigDecimal amount,
        int term,
        String issueDate
    ) {
        var options = new ArrayList<Map<String, Object>>();
        for (var product : products.catalog()) {
            boolean days = product.unit().equals("DAYS");
            if (products.reason(product, purpose, amount, days ? null : term, days ? term : null) != null)
                continue;
            var quote = products.calculate(product, amount, days ? null : term, days ? term : null);
            var option = new LinkedHashMap<String, Object>();
            option.put("product_id", product.id());
            option.put("name", product.name());
            option.put("amount", amount);
            option.put("annual_rate", product.rate());
            option.put("term", term);
            option.put("term_unit", product.unit());
            option.put("monthly_payment", quote.get("monthly_payment"));
            option.put("total_repay", quote.get("total_repay"));
            option.put("interest", quote.get("interest"));
            option.put("assessment", "NOT_ASSESSED");
            if (days) {
                LocalDate issue = LocalDate.parse(issueDate), maturity = issue.plusDays(term);
                var plan = object(object(context.get("calculations")).get("cashflow_6m"));
                BigDecimal balance = number(object(context.get("business")).get("balance"));
                for (var event : maps(plan.get("events")))
                    if (!LocalDate.parse(String.valueOf(event.get("event_date"))).isAfter(maturity))
                        balance = balance.add(number(event.get("amount")));
                BigDecimal after = balance.add(amount).subtract(number(quote.get("total_repay")));
                option.put("repayment_date", maturity);
                option.put("planned_balance_after_repayment", after);
                option.put("assessment", after.signum() < 0 ? "PLAN_SHORTFALL" : "PLAN_ONLY");
            }
            options.add(option);
        }
        options.sort(Comparator.comparing(option -> number(option.get("total_repay"))));
        return Map.of("status", options.isEmpty() ? "NO_MATCH" : "PRELIMINARY", "options", options,
            "note",
            "Предварительный расчёт по каталогу. Рост доходов и одобрение не предполагаются; платёжеспособность проверяется при оформлении.");
    }

    private static String text(
        Object value,
        int max
    ) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > max)
            throw new IllegalArgumentException("Invalid text");
        return text;
    }

    private static BigDecimal number(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> values) || values.stream().anyMatch(v -> !(v instanceof Map<?, ?>)))
            throw new IllegalArgumentException("Invalid list");
        return (List<Map<String, Object>>) value;
    }
}
