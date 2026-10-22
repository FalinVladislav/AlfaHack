package org.alfahack.features.drafts.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.alfahack.core.domain.Product;
import org.alfahack.core.domain.User;
import org.alfahack.core.domain.Util;
import org.alfahack.core.errors.DomainException;
import org.alfahack.core.transaction.UnitOfWork;
import org.alfahack.features.drafts.port.DraftsRepository;
import org.alfahack.features.products.port.ProductCatalog;
import org.alfahack.features.products.port.FinancingLimitUseCases;

public class Drafts implements org.alfahack.features.drafts.port.DraftUseCases {

    private final UnitOfWork unitOfWork;
    private final DraftsRepository repository;
    private final ProductCatalog products;
    private final FinancingLimitUseCases limits;
    private final LocalDate today;

    public Drafts(
        UnitOfWork unitOfWork,
        DraftsRepository repository,
        ProductCatalog products,
        FinancingLimitUseCases limits,
        String today
    ) {
        this.unitOfWork = unitOfWork;
        this.repository = repository;
        this.products = products;
        this.limits = limits;
        this.today = LocalDate.parse(today);
    }

    public Map<String, Object> get(
        User user,
        String id
    ) {
        user.client();
        var rows = repository.findOwned(id, user.company());
        if (rows.isEmpty())
            throw DomainException.missing();
        return rows.getFirst();
    }

    public List<Map<String, Object>> list(
        User user
    ) {
        user.client();
        return repository.listOwned(user.company());
    }

    public Map<String, Object> create(
        User user
    ) {
        return unitOfWork.required(() -> {
            user.client();
            String id = Util.id();
            repository.insert(id, user.company(), LocalDateTime.now());
            return get(user, id);

        });
    }

    public Map<String, Object> update(
        User user,
        String id,
        Map<String, Object> body
    ) {
        return unitOfWork.required(() -> {
            Map<String, Object> row = get(user, id);
            if (!"ACTIVE".equals(row.get("status")))
                throw DomainException.conflict("DRAFT_SUBMITTED", "Черновик уже отправлен");
            int expected = Util.number(body.get("expected_version"), 1, Integer.MAX_VALUE, "expected_version");
            if (expected != ((Number) row.get("version")).intValue())
                throw DomainException.conflict("DRAFT_VERSION_CONFLICT",
                    "Черновик изменён в другой вкладке. Обновите данные");
            String purpose = body.containsKey("purpose") ? Util.string(body, "purpose") : (String) row.get("purpose");
            if (purpose != null && !List.of("EXPANSION", "WORKING_CAPITAL", "CASH_GAP").contains(purpose))
                throw DomainException.bad("INVALID_PURPOSE", "Выберите цель финансирования");
            BigDecimal amount = body.containsKey("amount")
                ? Util.money(body.get("amount"))
                : (BigDecimal) row.get("amount");
            Integer months = body.containsKey("term_months")
                ? (body.get("term_months") == null ? null : Util.number(body.get("term_months"), 1, 120, "term_months"))
                : (Integer) row.get("term_months");
            Integer days = body.containsKey("duration_days")
                ? (body.get("duration_days") == null
                    ? null
                    : Util.number(body.get("duration_days"), 1, 365, "duration_days"))
                : (Integer) row.get("duration_days");
            LocalDate date = body.containsKey("needed_by")
                ? (body.get("needed_by") == null ? null : Util.date(body.get("needed_by"), "needed_by"))
                : row.get("needed_by") == null ? null : ((LocalDate) row.get("needed_by"));
            String product = body.containsKey("product_id")
                ? Util.string(body, "product_id")
                : (String) row.get("product_id");
            if (product != null && !product.isBlank()) {
                Product selected = products.get(product);
                if (purpose != null && !selected.purpose().equals(purpose)) {
                    if (body.containsKey("product_id"))
                        throw DomainException.bad("PURPOSE_MISMATCH", "Продукт не соответствует типу кредита");
                    product = null;
                }
            }
            if (purpose != null && purpose.equals("CASH_GAP"))
                months = null;
            else
                days = null;
            int changed = repository.updateVersion(purpose, amount, months, days, date, product, LocalDateTime.now(),
                id, user.company(), expected);
            if (changed != 1)
                throw DomainException.conflict("DRAFT_VERSION_CONFLICT", "Черновик изменён. Обновите данные");
            return get(user, id);

        });
    }

    public Map<String, Object> quote(
        User user,
        String id,
        Map<String, Object> body
    ) {
        Map<String, Object> d = get(user, id);
        int expected = Util.number(body.get("expected_version"), 1, Integer.MAX_VALUE, "expected_version");
        if (expected != ((Number) d.get("version")).intValue())
            throw DomainException.conflict("DRAFT_VERSION_CONFLICT", "Пересчитайте обновлённый черновик");
        BigDecimal amount = (BigDecimal) d.get("amount");
        String purpose = (String) d.get("purpose");
        Integer months = (Integer) d.get("term_months");
        Integer days = (Integer) d.get("duration_days");
        if (amount == null || purpose == null)
            throw DomainException.bad("INCOMPLETE_DRAFT", "Укажите цель и сумму");
        List<Map<String, Object>> options = new ArrayList<>();
        var available = limits.limits(user, purpose, null, (LocalDate) d.get("needed_by"));
        for (Product p : products.catalog()) {
            String reason = products.reason(p, purpose, amount, months, days);
            if (reason == null && !withinLimits(available, p.id(), amount, p.unit().equals("DAYS") ? days : months))
                reason = "PERSONAL_LIMIT";
            if (reason == null && d.get("needed_by") != null
                && ((LocalDate) d.get("needed_by")).isBefore(today.plusDays(1)))
                reason = "TOO_LATE";
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("product_id", p.id());
            option.put("name", p.name());
            option.put("reason_code", reason);
            option.put("annual_rate", p.rate());
            if (reason == null) {
                Map<String, Object> calc = products.calculate(p, amount, months, days);
                String qid = Util.id();
                repository.insertQuote(qid, id, expected, p.id(), amount, months, days, p.rate(),
                    (BigDecimal) calc.get("monthly_payment"), (BigDecimal) calc.get("total_repay"),
                    LocalDateTime.now());
                option.put("quote_id", qid);
                option.putAll(calc);
            }
            options.add(option);
        }
        return Map.of("draft_id", id, "draft_version", expected, "policy_version", "demo-v1", "options", options);
    }

    private boolean withinLimits(
        Map<String, Object> available,
        String product,
        BigDecimal amount,
        int term
    ) {
        for (Object item : (List<?>) available.get("products")) {
            var p = (Map<?, ?>) item;
            if (!product.equals(p.get("product_id")))
                continue;
            for (Object option : (List<?>) p.get("terms")) {
                var bounds = (Map<?, ?>) option;
                if (((Number) bounds.get("term")).intValue() == term
                    && amount.compareTo(new BigDecimal(bounds.get("min_amount").toString())) >= 0
                    && amount.compareTo(new BigDecimal(bounds.get("max_amount").toString())) <= 0)
                    return true;
            }
        }
        return false;
    }

    public Map<String, Object> quoteById(
        User user,
        String id
    ) {
        user.client();
        var rows = repository.findOwnedQuote(id, user.company());
        if (rows.isEmpty())
            throw DomainException.missing();
        return rows.getFirst();
    }
}
