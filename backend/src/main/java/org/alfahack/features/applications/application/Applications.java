package org.alfahack.features.applications.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.alfahack.core.domain.Product;
import org.alfahack.core.domain.Profile;
import org.alfahack.core.domain.User;
import org.alfahack.core.domain.Util;
import org.alfahack.core.errors.DomainException;
import org.alfahack.core.serialization.JsonCodec;
import org.alfahack.core.transaction.UnitOfWork;
import org.alfahack.features.applications.port.ApplicationsRepository;
import org.alfahack.features.drafts.port.DraftUseCases;
import org.alfahack.features.products.port.ProductCatalog;
import org.alfahack.features.scoring.port.ProfileCatalog;
import org.alfahack.features.scoring.port.RiskAssessment;

public class Applications implements org.alfahack.features.applications.port.ApplicationUseCases {

    private final UnitOfWork unitOfWork;
    private final ApplicationsRepository repository;
    private final DraftUseCases drafts;
    private final ProductCatalog products;
    private final RiskAssessment scoring;
    private final ProfileCatalog profiles;
    private final JsonCodec json;
    private final LocalDate today;

    public Applications(
        UnitOfWork unitOfWork,
        ApplicationsRepository repository,
        DraftUseCases drafts,
        ProductCatalog products,
        RiskAssessment scoring,
        ProfileCatalog profiles,
        JsonCodec json,
        String today
    ) {
        this.unitOfWork = unitOfWork;
        this.repository = repository;
        this.drafts = drafts;
        this.products = products;
        this.scoring = scoring;
        this.profiles = profiles;
        this.json = json;
        this.today = LocalDate.parse(today);
    }

    public List<Map<String, Object>> list(
        User user
    ) {
        return user.role().equals("CLIENT") ? repository.listOwned(user.company()) : repository.listAll();
    }

    public Map<String, Object> raw(
        User user,
        String id
    ) {
        var rows = user.role().equals("CLIENT") ? repository.findOwned(id, user.company()) : repository.findById(id);
        if (rows.isEmpty())
            throw DomainException.missing();
        return rows.getFirst();
    }

    public Map<String, Object> detail(
        User user,
        String id
    ) {
        Map<String, Object> a = raw(user, id);
        Map<String, Object> out = new LinkedHashMap<>(a);
        String status = (String) a.get("status");
        out.put("next_action", switch (status) {
            case "NEEDS_INFO" -> "PROVIDE_INFO";
            case "APPROVED", "COUNTEROFFER" -> "ACCEPT_OFFER";
            case "OFFER_ACCEPTED" -> "SIGN_CONTRACT";
            case "SIGNED" -> "DISBURSE";
            case "DISBURSED" -> "VIEW_LOAN";
            default -> "NONE";
        });
        out.put("requests", repository.requests(id));
        out.put("events", repository.events(id));
        var loans = repository.applicationLoans(id);
        if (!loans.isEmpty())
            out.put("loan", loans.getFirst());
        var reports = repository.reports(id);
        if (!reports.isEmpty())
            try {
                out.put("decision_report", json.read((String) reports.getFirst().get("report_json")));
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        if (user.role().equals("CLIENT")) {
            out.remove("score");
            out.remove("demo_profile");
        }
        return out;
    }

    public Map<String, Object> submit(
        User user,
        Map<String, Object> body,
        String key
    ) {
        return unitOfWork.required(() -> {
            user.client();
            String draftId = Util.required(body, "draft_id"), quoteId = Util.required(body, "quote_id");
            String bodyHash = Util
                .hash(draftId + "|" + quoteId + "|" + body.get("expected_version") + "|" + body.get("consents"));
            String old = existing(user, "SUBMIT", key, bodyHash);
            if (old != null)
                return detail(user, old);
            if (!Boolean.TRUE.equals(body.get("consents")))
                throw DomainException.bad("CONSENT_REQUIRED", "Подтвердите согласие перед отправкой");
            Map<String, Object> d = drafts.get(user, draftId), q = drafts.quoteById(user, quoteId);
            int expected = Util.number(body.get("expected_version"), 1, Integer.MAX_VALUE, "expected_version");
            if (!"ACTIVE".equals(d.get("status")) || !draftId.equals(q.get("draft_id"))
                || expected != ((Number) d.get("version")).intValue()
                || expected != ((Number) q.get("draft_version")).intValue())
                throw DomainException.conflict("QUOTE_STALE", "Расчёт устарел. Пересчитайте условия");
            if (d.get("needed_by") == null)
                throw DomainException.bad("NEEDED_BY_REQUIRED", "Укажите дату, к которой нужны деньги");
            int locked = repository.submitDraft(LocalDateTime.now(), draftId, user.company(), expected);
            if (locked != 1)
                throw DomainException.conflict("QUOTE_STALE", "Черновик изменился. Пересчитайте условия");
            String id = Util.id();
            LocalDateTime now = LocalDateTime.now();
            repository.insert(id, user.company(), draftId, quoteId, "SUBMITTED", null, (BigDecimal) q.get("amount"),
                (Integer) q.get("term_months"), (Integer) q.get("duration_days"), (String) q.get("product_id"),
                (BigDecimal) q.get("annual_rate"), (BigDecimal) q.get("monthly_payment"),
                (BigDecimal) q.get("total_repay"), null, null, 0, 1, now, now);
            event(id, user.key(), null, "SUBMITTED", null);
            storeKey(user, "SUBMIT", key, bodyHash, id);
            check(new User("SYSTEM", "STAFF", null, ""), id, null);
            return detail(user, id);

        });
    }

    public Map<String, Object> check(
        User user,
        String id,
        String profile
    ) {
        return unitOfWork.required(() -> {
            user.staff();
            Map<String, Object> a = raw(user, id);
            String bound = profiles.companyKey((String) a.get("company_id"));
            if (profile != null && !profile.equals(bound))
                throw DomainException.conflict("PROFILE_LOCKED", "Используются только данные компании-заявителя");
            String status = (String) a.get("status");
            if (!List.of("SUBMITTED", "CHECKING", "NEEDS_INFO").contains(status))
                return detail(user, id);
            if (a.get("demo_profile") != null && !bound.equals(a.get("demo_profile")))
                throw DomainException.conflict("LEGACY_PROFILE",
                    "Старая заявка использует другой набор данных. Создайте новую заявку");
            repository.lockProfile(bound, id);
            a = raw(user, id);
            if (status.equals("NEEDS_INFO")) {
                int pending = repository.pendingRequests(id);
                if (pending > 0)
                    throw DomainException.conflict("INFO_PENDING", "Сначала подтвердите запрошенные сведения");
            }
            transition(id, user.key(), status, "CHECKING", null);
            String selected = bound;
            int verified = repository.verifiedRequests(id);
            BigDecimal amount = (BigDecimal) a.get("amount");
            BigDecimal payment = (BigDecimal) a.get("monthly_payment");
            String product = (String) a.get("product_id");
            BigDecimal existingDebt = (BigDecimal) repository.companyBalance((String) a.get("company_id"))
                .get("monthly_debt");
            event(id, "RULES", "CHECKING", "CHECKING", "ASSESSING_TERMS");
            Map<String, Object> report = new LinkedHashMap<>(
                scoring.assess("MISSING_INFO".equals(selected) && verified > 0 ? "GOOD" : selected, amount,
                    payment == null ? BigDecimal.ZERO : payment, existingDebt));
            report.put("company_id", a.get("company_id"));
            report.put("assessed_at", LocalDateTime.now().toString());
            report.put("data_sources", Map.of("operations", "transactions: два последних завершённых месяца",
                "current_debt", "existing_loans + loans", "assets_and_collateral",
                "Известный синтетический снимок компании"));
            Profile financialInput = profiles.get(selected);
            report.put("free_cashflow", financialInput.monthlyProfit().subtract(existingDebt));
            report.put("payment_limit",
                financialInput.monthlyProfit().subtract(existingDebt).multiply(new BigDecimal("0.50")));
            report.put("term_assumption",
                "Месячные показатели постоянны на весь срок; новые доходы от инвестиций не предполагаются");
            try {
                String serialized = json.write(report);
                repository.deleteReport(id);
                repository.insertReport(id, serialized);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            if ("SOURCE_ERROR".equals(report.get("outcome"))) {
                transition(id, "MOCKS", "CHECKING", "CHECKING", "SOURCE_UNAVAILABLE");
                return detail(user, id);
            }
            if ("INSUFFICIENT_DATA".equals(report.get("outcome"))) {
                repository.insertMissingIncomeRequest(Util.id(), id, "INCOME_UNKNOWN", "FINANCIAL_STATEMENT", "2026-09",
                    "OPEN");
                transition(id, "RULES", "CHECKING", "NEEDS_INFO", "INCOME_UNKNOWN");
                return detail(user, id);
            }
            int score = ((Number) report.get("adjusted_score")).intValue();
            repository.updateScore(score, id);
            if ("REJECTED".equals(report.get("outcome"))) {
                transition(id, "RULES", "CHECKING", "REJECTED", (String) report.get("code"));
                return detail(user, id);
            }
            Profile input = profiles.get(selected);
            BigDecimal income = input.monthlyRevenue(), costs = income.subtract(input.monthlyProfit()),
                debt = existingDebt;
            BigDecimal free = income.subtract(costs).subtract(debt);
            if (free.signum() <= 0) {
                transition(id, "RULES", "CHECKING", "REJECTED", "NO_FREE_CASHFLOW");
                return detail(user, id);
            }
            BigDecimal allowed = free.multiply(new BigDecimal("0.50"));
            if (product.equals("OVERDRAFT")) {
                LocalDate issue = ((LocalDate) repository.draftDate((String) a.get("draft_id")).get("needed_by"));
                LocalDate due = issue.plusDays(((Number) a.get("duration_days")).longValue());
                BigDecimal starting = (BigDecimal) repository.companyBalance((String) a.get("company_id"))
                    .get("balance");
                BigDecimal planned = (BigDecimal) repository.plannedBalance((String) a.get("company_id"), today, due)
                    .get("total");
                BigDecimal interest = ((BigDecimal) a.get("total_repay")).subtract(amount);
                if (starting.add(planned).subtract(interest).signum() < 0) {
                    transition(id, "RULES", "CHECKING", "REJECTED", "PAYBACK_SHORTFALL");
                    return detail(user, id);
                }
                if ("CONDITIONAL".equals(report.get("outcome"))) {
                    BigDecimal reduced = amount.multiply(new BigDecimal("0.70")).setScale(2, RoundingMode.DOWN);
                    Product p = products.get(product);
                    if (reduced.compareTo(p.min()) < 0) {
                        transition(id, "RULES", "CHECKING", "REJECTED", "RISK_LIMIT");
                        return detail(user, id);
                    }
                    Map<String, Object> calc = products.calculate(p, reduced, null, (Integer) a.get("duration_days"));
                    repository.reduceOverdraft(reduced, (BigDecimal) calc.get("total_repay"), id);
                    transition(id, "RULES", "CHECKING", "COUNTEROFFER", "RISK_LIMIT");
                    return detail(user, id);
                }
            }
            if (!product.equals("OVERDRAFT")
                && (payment.compareTo(allowed) > 0 || "CONDITIONAL".equals(report.get("outcome")))) {
                BigDecimal reduced = payment.compareTo(allowed) > 0
                    ? amount.multiply(allowed).divide(payment, 2, RoundingMode.DOWN)
                    : amount.multiply(new BigDecimal("0.70")).setScale(2, RoundingMode.DOWN);
                if (reduced.compareTo(amount) >= 0)
                    reduced = amount.multiply(new BigDecimal("0.70")).setScale(2, RoundingMode.DOWN);
                if (score < 75)
                    reduced = reduced.min(amount.multiply(new BigDecimal("0.5")));
                Product p = products.get(product);
                if (reduced.compareTo(p.min()) < 0) {
                    transition(id, "RULES", "CHECKING", "REJECTED", "PAYMENT_LIMIT");
                    return detail(user, id);
                }
                Map<String, Object> calc = products.calculate(p, reduced, (Integer) a.get("term_months"), null);
                repository.reduceLoan(reduced, (BigDecimal) calc.get("monthly_payment"),
                    (BigDecimal) calc.get("total_repay"), id);
                transition(id, "RULES", "CHECKING", "COUNTEROFFER",
                    payment.compareTo(allowed) > 0 ? "PAYMENT_LIMIT" : "RISK_LIMIT");
                return detail(user, id);
            }
            transition(id, "RULES", "CHECKING", "APPROVED", "POLICY_PASSED");
            return detail(user, id);

        });
    }

    public Map<String, Object> accept(
        User user,
        String id,
        Map<String, Object> body,
        String key
    ) {
        return unitOfWork.required(() -> {
            user.client();
            String hash = Util.hash(id + "|" + body);
            String old = existing(user, "ACCEPT", key, hash);
            if (old != null)
                return detail(user, old);
            Map<String, Object> a = raw(user, id);
            if (!List.of("APPROVED", "COUNTEROFFER").contains(a.get("status")))
                throw DomainException.conflict("INVALID_STATE", "Предложение недоступно");
            if (Util.number(body.get("offer_version"), 0, Integer.MAX_VALUE,
                "offer_version") != ((Number) a.get("offer_version")).intValue())
                throw DomainException.conflict("OFFER_STALE", "Условия изменились. Обновите заявку");
            transition(id, user.key(), (String) a.get("status"), "OFFER_ACCEPTED", null);
            storeKey(user, "ACCEPT", key, hash, id);
            return detail(user, id);

        });
    }

    public Map<String, Object> decline(
        User user,
        String id
    ) {
        return unitOfWork.required(() -> {
            user.client();
            Map<String, Object> a = raw(user, id);
            if (!List.of("APPROVED", "COUNTEROFFER").contains(a.get("status")))
                throw DomainException.conflict("INVALID_STATE", "Предложение недоступно");
            transition(id, user.key(), (String) a.get("status"), "CLOSED", "CLIENT_DECLINED");
            return detail(user, id);

        });
    }

    public Map<String, Object> sign(
        User user,
        String id,
        Map<String, Object> body,
        String key
    ) {
        return unitOfWork.required(() -> {
            user.client();
            String hash = Util.hash(id + "|" + body);
            String old = existing(user, "SIGN", key, hash);
            if (old != null)
                return detail(user, old);
            Map<String, Object> a = raw(user, id);
            if (!"OFFER_ACCEPTED".equals(a.get("status")))
                throw DomainException.conflict("INVALID_STATE", "Сначала примите предложение");
            if (!Boolean.TRUE.equals(body.get("confirmation")))
                throw DomainException.bad("CONFIRMATION_REQUIRED", "Подтвердите подписание");
            transition(id, user.key(), "OFFER_ACCEPTED", "SIGNED", null);
            storeKey(user, "SIGN", key, hash, id);
            return detail(user, id);

        });
    }

    public Map<String, Object> disburse(
        User user,
        String id,
        String key
    ) {
        return unitOfWork.required(() -> {
            user.client();
            String old = existing(user, "DISBURSE", key, Util.hash(id));
            if (old != null)
                return detail(user, old);
            Map<String, Object> a = raw(user, id);
            if (!"SIGNED".equals(a.get("status")))
                throw DomainException.conflict("INVALID_STATE", "Договор ещё не подписан");
            String loanId = Util.id();
            BigDecimal amount = (BigDecimal) a.get("amount");
            BigDecimal next = (BigDecimal) a.get("monthly_payment");
            if ("OVERDRAFT".equals(a.get("product_id")))
                next = (BigDecimal) a.get("total_repay");
            LocalDate due = "OVERDRAFT".equals(a.get("product_id"))
                ? today.plusDays(((Number) a.get("duration_days")).longValue())
                : today.plusMonths(1);
            repository.insertLoan(loanId, id, user.company(), amount, amount, due, next, LocalDateTime.now());
            repository.insertLedgerEvent(Util.id(), user.company(), today, amount, "DISBURSEMENT",
                "Выдача кредита " + loanId);
            repository.creditCompany(amount, user.company());
            if (!"CASH_GAP".equals(repository.draftDate((String) a.get("draft_id")).get("purpose"))) {
                repository.insertLedgerEvent(Util.id(), user.company(), today, amount.negate(), "INVESTMENT_SPEND",
                    "Целевое расходование кредита");
                repository.creditCompany(amount.negate(), user.company());
            }
            transition(id, user.key(), "SIGNED", "DISBURSED", null);
            storeKey(user, "DISBURSE", key, Util.hash(id), id);
            return detail(user, id);

        });
    }

    public Map<String, Object> contract(
        User user,
        String id
    ) {
        Map<String, Object> a = raw(user, id);
        if (!List.of("OFFER_ACCEPTED", "SIGNED", "DISBURSED").contains(a.get("status")))
            throw DomainException.conflict("INVALID_STATE", "Договор ещё не готов");
        return Map.of("number", "CR-" + id.substring(0, 8).toUpperCase(), "label", "Кредитный договор", "company_id",
            a.get("company_id"), "product_id", a.get("product_id"), "amount", a.get("amount"), "annual_rate",
            a.get("annual_rate"), "total_repay", a.get("total_repay"), "offer_version", a.get("offer_version"));
    }

    public List<Map<String, Object>> loans(
        User user
    ) {
        user.client();
        return repository.loans(user.company());
    }

    public Map<String, Object> loan(
        User user,
        String id
    ) {
        user.client();
        var rows = repository.loan(id, user.company());
        if (rows.isEmpty())
            throw DomainException.missing();
        return rows.getFirst();
    }

    private void event(
        String id,
        String actor,
        String old,
        String next,
        String reason
    ) {
        repository.insertEvent(Util.id(), id, actor, old, next, reason, LocalDateTime.now());
    }

    private void transition(
        String id,
        String actor,
        String old,
        String next,
        String reason
    ) {
        int changed = repository.transition(next, reason, LocalDateTime.now(), id, old);
        if (changed != 1)
            throw DomainException.conflict("STATE_CHANGED", "Заявка изменилась. Обновите данные");
        event(id, actor, old, next, reason);
    }

    private String existing(
        User user,
        String operation,
        String key,
        String hash
    ) {
        if (key == null || key.isBlank())
            throw DomainException.bad("IDEMPOTENCY_KEY_REQUIRED", "Повторите запрос с ключом Idempotency-Key");
        var rows = repository.idempotencyRecord(user.key(), operation, key);
        if (rows.isEmpty())
            return null;
        if (!hash.equals(rows.getFirst().get("body_hash")))
            throw DomainException.conflict("IDEMPOTENCY_CONFLICT", "Этот ключ уже использован для другого запроса");
        return (String) rows.getFirst().get("result_id");
    }

    private void storeKey(
        User user,
        String operation,
        String key,
        String hash,
        String id
    ) {
        repository.storeIdempotency(user.key(), operation, key, hash, id);
    }
}
