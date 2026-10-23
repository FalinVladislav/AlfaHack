package org.alfahack.features.applications.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.alfahack.core.domain.Product;
import org.alfahack.core.domain.Profile;
import org.alfahack.core.domain.User;
import org.alfahack.core.domain.Util;
import org.alfahack.core.errors.DomainException;
import org.alfahack.core.serialization.JsonCodec;
import org.alfahack.core.transaction.UnitOfWork;
import org.alfahack.features.applications.port.ApplicationUseCases;
import org.alfahack.features.applications.port.ApplicationsRepository;
import org.alfahack.features.drafts.port.DraftUseCases;
import org.alfahack.features.products.port.ProductCatalog;
import org.alfahack.features.scoring.port.ProfileCatalog;
import org.alfahack.features.scoring.port.RiskAssessment;

/**
 * A recommendation, an employee decision, acceptance, signature and
 * disbursement are separate persisted events. All commands use state/version
 * compare-and-set.
 */
public class Applications implements ApplicationUseCases {

    private static final User SYSTEM = new User("SYSTEM", "STAFF", null, "");
    private final UnitOfWork unitOfWork;
    private final ApplicationsRepository repository;
    private final DraftUseCases drafts;
    private final ProductCatalog products;
    private final RiskAssessment scoring;
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
        this.json = json;
        this.today = LocalDate.parse(today);
    }

    public List<Map<String, Object>> list(User user) {
        var rows = user.role().equals("CLIENT") ? repository.listOwned(user.company()) : repository.listAll();
        for (var row : rows)
            expire(user, (String) row.get("id"));
        rows = user.role().equals("CLIENT") ? repository.listOwned(user.company()) : repository.listAll();
        List<Map<String, Object>> result = new ArrayList<>();
        for (var row : rows) {
            Map<String, Object> item = new LinkedHashMap<>(row);
            if (user.role().equals("STAFF")) {
                Map<String, Object> report = report((String) row.get("id"));
                item.put("recommendation", report == null ? null : report.get("outcome"));
                item.put("risk_category", riskCategory(report));
            }
            result.add(item);
        }
        return result;
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
        expire(user, id);
        a = raw(user, id);
        Map<String, Object> out = new LinkedHashMap<>(a);
        boolean staff = user.role().equals("STAFF");
        Map<String, Object> request = repository.snapshot(id);
        Map<String, Object> company = repository.company((String) a.get("company_id"));
        out.put("request", request);
        out.put("company", company);
        out.put("obligations", repository.existingLoans((String) a.get("company_id")));
        if (staff)
            out.put("events", repository.events(id));
        else {
            List<Map<String, Object>> publicEvents = new ArrayList<>();
            for (var event : repository.events(id)) {
                Map<String, Object> visible = new LinkedHashMap<>(event);
                visible.remove("reason_code");
                visible.remove("details");
                String actor = (String) visible.get("actor");
                visible.put("actor", user.key().equals(actor)
                    ? "Клиент"
                    : "SYSTEM".equals(actor) || "RULES".equals(actor) ? "Система банка" : "Сотрудник банка");
                publicEvents.add(visible);
            }
            out.put("events", publicEvents);
        }
        out.put("requests", repository.requests(id));
        var offers = repository.offers(id);
        Map<String, Object> offer = offers.isEmpty() ? null : offers.getFirst();
        out.put("offer", offer);
        out.put("offer_version", offer == null ? 0 : offer.get("version"));
        if (offer != null) {
            Map<String, Object> calculation = products.calculate(products.get((String) offer.get("product_id")),
                (BigDecimal) offer.get("amount"), (Integer) offer.get("term_months"),
                (Integer) offer.get("duration_days"));
            out.put("offer_schedule", calculation.get("schedule"));
        }
        var decisions = repository.decisions(id);
        if (!decisions.isEmpty()) {
            Map<String, Object> decision = new LinkedHashMap<>(decisions.getFirst());
            if (!staff) {
                decision.remove("internal_reason");
                decision.remove("divergence_reason");
                decision.remove("scoring_snapshot");
                decision.remove("reason_code");
                decision.remove("reason");
                decision.remove("application_version");
            }
            out.put("bank_decision", decision);
        }
        var responses = repository.responses(id);
        if (!responses.isEmpty())
            out.put("client_response", responses.getFirst());
        var contracts = repository.contracts(id);
        if (!contracts.isEmpty())
            out.put("contract", contracts.getFirst());
        var loans = repository.applicationLoans(id);
        if (!loans.isEmpty()) {
            out.put("loan", loans.getFirst());
            out.put("schedule", repository.schedule((String) loans.getFirst().get("id")));
        }
        Map<String, Object> report = report(id);
        if (staff) {
            out.put("decision_report", report);
            out.put("risk_category", riskCategory(report));
            List<Map<String, Object>> scoringHistory = new ArrayList<>();
            for (var run : repository.scoringRuns(id)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("assessed_at", run.get("assessed_at"));
                item.put("report", json.read((String) run.get("report_json")));
                scoringHistory.add(item);
            }
            out.put("scoring_history", scoringHistory);
            var reportRows = repository.reports(id);
            out.put("scored_at", reportRows.isEmpty() ? null : reportRows.getFirst().get("assessed_at"));
        } else {
            out.remove("score");
            out.remove("demo_profile");
            out.remove("reason_code");
            out.remove("assigned_to_internal");
            out.put("decision_report", null);
        }
        out.put("next_action", nextAction((String) a.get("status")));
        return out;
    }

    private String nextAction(String status) {
        return switch (status) {
            case "SUBMITTED", "CHECKING" -> "SCORING";
            case "SCORING_ERROR" -> "SCORING_RETRY";
            case "NEEDS_INFO" -> "CLIENT_DOCUMENT";
            case "AWAITING_DECISION" -> "STAFF_CLAIM";
            case "IN_REVIEW" -> "STAFF_DECISION";
            case "APPROVED", "COUNTEROFFER" -> "CLIENT_RESPONSE";
            case "OFFER_ACCEPTED" -> "CLIENT_SIGNATURE";
            case "SIGNED" -> "DISBURSEMENT_RETRY";
            case "DISBURSED" -> "VIEW_LOAN";
            default -> "NONE";
        };
    }

    public Map<String, Object> submit(
        User user,
        Map<String, Object> body,
        String key
    ) {
        return unitOfWork.required(() -> {
            user.client();
            String draftId = Util.required(body, "draft_id"), quoteId = Util.required(body, "quote_id");
            String hash = Util.hash(draftId + "|" + quoteId + "|" + body.get("expected_version") + "|"
                + body.get("consents"));
            String old = existing(user, "SUBMIT", key, hash);
            if (old != null)
                return detail(user, old);
            if (!Boolean.TRUE.equals(body.get("consents")))
                throw DomainException.bad("CONSENT_REQUIRED", "Подтвердите согласие перед отправкой");
            Map<String, Object> d = drafts.get(user, draftId), q = drafts.quoteById(user, quoteId);
            int expected = Util.number(body.get("expected_version"), 1, Integer.MAX_VALUE, "expected_version");
            if (!"ACTIVE".equals(d.get("status")) || !draftId.equals(q.get("draft_id"))
                || expected != number(d.get("version")) || expected != number(q.get("draft_version")))
                throw DomainException.conflict("QUOTE_STALE", "Расчёт устарел. Пересчитайте условия");
            if (d.get("needed_by") == null)
                throw DomainException.bad("NEEDED_BY_REQUIRED", "Укажите дату, к которой нужны деньги");
            LocalDateTime now = LocalDateTime.now();
            if (repository.submitDraft(now, draftId, user.company(), expected) != 1)
                throw DomainException.conflict("QUOTE_STALE", "Черновик изменился. Пересчитайте условия");
            String id = Util.id();
            repository.insert(id, user.company(), draftId, quoteId, "SUBMITTED", null,
                (BigDecimal) q.get("amount"), (Integer) q.get("term_months"), (Integer) q.get("duration_days"),
                (String) q.get("product_id"), (BigDecimal) q.get("annual_rate"),
                (BigDecimal) q.get("monthly_payment"), (BigDecimal) q.get("total_repay"),
                null, null, 0, 1, now, now);
            repository.insertSnapshot(id, d, q, now);
            audit(id, user.key(), null, "SUBMITTED", "SUBMITTED", "Заявка отправлена");
            storeKey(user, "SUBMIT", key, hash, id);
            check(SYSTEM, id, null);
            return detail(user, id);
        });
    }

    public Map<String, Object> check(
        User user,
        String id,
        String profile
    ) {
        return unitOfWork.required(() -> {
            if (!"SYSTEM".equals(user.key()))
                user.staff();
            if (profile != null && !profile.isBlank())
                throw DomainException.bad("PROFILE_NOT_ALLOWED", "Оценка использует сведения компании из системы");
            Map<String, Object> a = raw(user, id);
            String status = (String) a.get("status");
            boolean legacyRecommendation = "AWAITING_DECISION".equals(status)
                && a.get("demo_profile") != null
                && repository.reports(id).stream().allMatch(row -> row.get("assessed_at") == null);
            if (!List.of("SUBMITTED", "CHECKING", "SCORING_ERROR", "NEEDS_INFO").contains(status)
                && !legacyRecommendation)
                throw DomainException.conflict("INVALID_STATE", "Повторная оценка сейчас недоступна");
            if ("NEEDS_INFO".equals(status) && repository.pendingRequests(id) > 0)
                throw DomainException.conflict("INFO_PENDING", "Сначала подтвердите запрошенные сведения");
            if (!"CHECKING".equals(status))
                transition(id, "SYSTEM", status, "CHECKING", null);
            else
                audit(id, "SYSTEM", "CHECKING", "CHECKING", "SCORING_STARTED", "Повторный расчёт");
            Map<String, Object> report = assess(a, (BigDecimal) a.get("amount"),
                (BigDecimal) a.get("monthly_payment"), (Integer) a.get("duration_days"),
                (BigDecimal) a.get("total_repay"));
            repository.deleteReport(id);
            repository.insertReport(id, json.write(report));
            String outcome = (String) report.get("outcome");
            if ("SOURCE_ERROR".equals(outcome)) {
                transition(id, "SYSTEM", "CHECKING", "SCORING_ERROR", (String) report.get("code"));
            } else
                if ("INSUFFICIENT_DATA".equals(outcome)) {
                    if (repository.requests(id).isEmpty())
                        repository.insertRequest(Util.id(), id, (String) report.get("code"),
                            "FINANCIAL_STATEMENT", null, "OPEN");
                    transition(id, "SYSTEM", "CHECKING", "NEEDS_INFO", (String) report.get("code"));
                } else {
                    repository.score(id, number(report.get("adjusted_score")), LocalDateTime.now());
                    transition(id, "SYSTEM", "CHECKING", "AWAITING_DECISION", null);
                    audit(id, "SYSTEM", "AWAITING_DECISION", "AWAITING_DECISION", "SCORING_RESULT",
                        "Рекомендация: " + outcome);
                }
            return detail(user, id);
        });
    }

    private Map<String, Object> assess(
        Map<String, Object> application,
        BigDecimal amount,
        BigDecimal payment,
        Integer days,
        BigDecimal total
    ) {
        Profile profile = companyProfile((String) application.get("company_id"));
        if (profile == null)
            return Map.of("outcome", "INSUFFICIENT_DATA", "code", "FINANCIAL_DATA_MISSING",
                "explanation", "Недостаточно финансовых сведений для расчёта.");
        BigDecimal monthly = payment == null ? BigDecimal.ZERO : payment;
        if (days != null)
            monthly = total.multiply(BigDecimal.valueOf(Math.min(30.0 / days, 1.0)))
                .setScale(2, RoundingMode.HALF_UP);
        return scoring.assess(profile, amount, monthly);
    }

    private Profile companyProfile(String companyId) {
        Map<String, Object> company = repository.company(companyId);
        if (company.get("current_assets") == null || company.get("current_liabilities") == null
            || company.get("collateral_value") == null || company.get("business_age") == null
            || company.get("overdue_days") == null || company.get("credit_history") == null)
            return null;
        YearMonth recent = YearMonth.from(today).minusMonths(1), previous = recent.minusMonths(1);
        BigDecimal revenue = BigDecimal.ZERO, priorRevenue = BigDecimal.ZERO, expenses = BigDecimal.ZERO;
        for (var row : repository.transactions(companyId)) {
            YearMonth month = YearMonth.from((LocalDate) row.get("date"));
            BigDecimal value = (BigDecimal) row.get("amount");
            if ("INCOME".equals(row.get("type"))) {
                if (recent.equals(month))
                    revenue = revenue.add(value);
                if (previous.equals(month))
                    priorRevenue = priorRevenue.add(value);
            } else
                if (recent.equals(month))
                    expenses = expenses.add(value);
        }
        if (revenue.signum() <= 0 || priorRevenue.signum() <= 0)
            return null;
        BigDecimal debt = BigDecimal.ZERO;
        for (var loan : repository.existingLoans(companyId))
            debt = debt.add((BigDecimal) loan.get("monthly_payment"));
        for (var loan : repository.loans(companyId))
            debt = debt.add((BigDecimal) loan.get("next_payment_amount"));
        return new Profile(companyId, Objects.toString(company.get("industry"), "Нет сведений"),
            number(company.get("business_age")), 0, revenue, priorRevenue,
            revenue.subtract(expenses).add(debt), debt, (BigDecimal) company.get("current_assets"),
            (BigDecimal) company.get("current_liabilities"), (BigDecimal) company.get("collateral_value"),
            number(company.get("overdue_days")),
            true, Objects.toString(company.get("credit_history"), "UNKNOWN"));
    }

    public Map<String, Object> claim(
        User user,
        String id,
        int expectedVersion
    ) {
        return unitOfWork.required(() -> {
            user.staff();
            raw(user, id);
            if (repository.claim(id, user.key(), expectedVersion, LocalDateTime.now()) != 1)
                throw DomainException.conflict("ALREADY_ASSIGNED",
                    "Заявку уже взял другой сотрудник или её состояние изменилось. Обновите список.");
            audit(id, user.key(), "AWAITING_DECISION", "IN_REVIEW", "CLAIMED", "Заявка взята в работу");
            return detail(user, id);
        });
    }

    public Map<String, Object> preview(
        User user,
        String id,
        Map<String, Object> body
    ) {
        user.staff();
        Map<String, Object> a = raw(user, id);
        assigned(user, a);
        if (!"IN_REVIEW".equals(a.get("status")))
            throw DomainException.conflict("INVALID_STATE", "Решение сейчас недоступно");
        Map<String, Object> request = repository.snapshot(id);
        String productId = (String) request.get("product_id");
        Product product = products.get(productId);
        BigDecimal amount = body.get("amount") == null
            ? (BigDecimal) request.get("amount")
            : Util.money(body.get("amount"));
        Integer months = product.unit().equals("MONTHS")
            ? (body.get("term_months") == null
                ? (Integer) request.get("term_months")
                : Util.number(body.get("term_months"), 1, 120, "term_months"))
            : null;
        Integer days = product.unit().equals("DAYS")
            ? (body.get("duration_days") == null
                ? (Integer) request.get("duration_days")
                : Util.number(body.get("duration_days"), 1, 365, "duration_days"))
            : null;
        String reason = products.reason(product, (String) request.get("purpose"), amount, months, days);
        if (reason != null)
            throw DomainException.bad(reason, "Сумма или срок выходят за пределы продукта");
        Map<String, Object> calc = products.calculate(product, amount, months, days);
        Map<String, Object> report = assess(a, amount, (BigDecimal) calc.get("monthly_payment"), days,
            (BigDecimal) calc.get("total_repay"));
        if (List.of("SOURCE_ERROR", "INSUFFICIENT_DATA").contains(report.get("outcome")))
            throw DomainException.conflict("SCORING_UNAVAILABLE", "Нет актуальной оценки для этих условий");
        String token = Util.hash(id + "|" + a.get("version") + "|" + productId + "|" + amount + "|"
            + months + "|" + days + "|" + json.write(report));
        Map<String, Object> result = new LinkedHashMap<>(calc);
        result.put("product_id", productId);
        result.put("amount", amount);
        result.put("term_months", months);
        result.put("duration_days", days);
        result.put("annual_rate", product.rate());
        result.put("report", report);
        result.put("calculation_token", token);
        result.put("application_version", a.get("version"));
        return result;
    }

    public Map<String, Object> decide(
        User user,
        String id,
        Map<String, Object> body
    ) {
        return unitOfWork.required(() -> {
            user.staff();
            Map<String, Object> a = raw(user, id);
            assigned(user, a);
            int expected = Util.number(body.get("expected_version"), 1, Integer.MAX_VALUE, "expected_version");
            if (!"IN_REVIEW".equals(a.get("status")) || number(a.get("version")) != expected)
                throw DomainException.conflict("STATE_CHANGED", "Заявка изменилась. Обновите карточку");
            String type = Util.required(body, "decision_type");
            if (!List.of("APPROVE", "COUNTEROFFER", "REJECT").contains(type))
                throw DomainException.bad("INVALID_DECISION", "Выберите решение");
            Map<String, Object> preview = preview(user, id, body);
            if (!Objects.equals(body.get("calculation_token"), preview.get("calculation_token")))
                throw DomainException.conflict("CALCULATION_STALE", "Расчёт устарел. Пересчитайте условия");
            Map<String, Object> request = repository.snapshot(id);
            boolean changed = ((BigDecimal) preview.get("amount")).compareTo((BigDecimal) request.get("amount")) != 0
                || !Objects.equals(preview.get("term_months"), request.get("term_months"))
                || !Objects.equals(preview.get("duration_days"), request.get("duration_days"));
            if ("APPROVE".equals(type) && changed)
                throw DomainException.bad("USE_COUNTEROFFER", "Для других условий выберите встречное предложение");
            if ("COUNTEROFFER".equals(type) && !changed)
                throw DomainException.bad("TERMS_UNCHANGED", "Укажите новую сумму или срок");
            String internal = meaningful(body, "internal_reason", 10);
            String message = meaningful(body, "client_message", 5);
            String reason = ("REJECT".equals(type) || "COUNTEROFFER".equals(type))
                ? meaningful(body, "reason", 5)
                : null;
            if ("Иная причина".equals(reason))
                throw DomainException.bad("REASON_REQUIRED", "Уточните причину решения");
            String recommendation = (String) ((Map<?, ?>) preview.get("report")).get("outcome");
            boolean divergence = ("REJECTED".equals(recommendation) && !"REJECT".equals(type))
                || ("APPROVED".equals(recommendation) && "REJECT".equals(type));
            String divergenceReason = divergence
                ? meaningful(body, "divergence_reason", 15)
                : null;
            String next = switch (type) {
                case "APPROVE" -> "APPROVED";
                case "COUNTEROFFER" -> "COUNTEROFFER";
                default -> "REJECTED";
            };
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime expiry = null;
            if (!"REJECT".equals(type)) {
                try {
                    expiry = LocalDateTime.parse(Util.required(body, "expires_at"));
                } catch (Exception ex) {
                    throw DomainException.bad("INVALID_EXPIRY", "Укажите срок действия предложения");
                }
                if (!expiry.isAfter(now) || expiry.isAfter(now.plusDays(30)))
                    throw DomainException.bad("INVALID_EXPIRY", "Срок предложения: от будущего момента до 30 дней");
            }
            if (repository.move(id, "IN_REVIEW", next, expected, user.key(), null, now) != 1)
                throw DomainException.conflict("STATE_CHANGED", "Решение уже принято или заявка отозвана");
            String offerId = null;
            if (!"REJECT".equals(type)) {
                offerId = Util.id();
                repository.insertOffer(offerId, id, 1, (String) preview.get("product_id"),
                    (BigDecimal) preview.get("amount"), (Integer) preview.get("term_months"),
                    (Integer) preview.get("duration_days"), (BigDecimal) preview.get("annual_rate"),
                    (BigDecimal) preview.get("monthly_payment"), (BigDecimal) preview.get("total_repay"),
                    expiry, now);
                audit(id, user.key(), next, next, "OFFER_CREATED", "Версия 1");
            }
            repository.insertDecision(id, expected, user.key(), type, offerId,
                json.write(preview.get("report")), internal, message, divergenceReason, reason, now);
            audit(id, user.key(), "IN_REVIEW", next, "BANK_DECISION",
                type + (divergence ? "; расхождение с рекомендацией обосновано" : ""));
            repository.insertNotification(Util.id(), id, (String) a.get("company_id"), message, now);
            audit(id, "SYSTEM", next, next, "NOTIFICATION_CREATED", "Уведомление доступно клиенту");
            return detail(user, id);
        });
    }

    public Map<String, Object> withdraw(
        User user,
        String id,
        int expectedVersion
    ) {
        return unitOfWork.required(() -> {
            user.client();
            Map<String, Object> a = raw(user, id);
            String status = (String) a.get("status");
            if (!List.of("SUBMITTED", "CHECKING", "SCORING_ERROR", "NEEDS_INFO",
                "AWAITING_DECISION", "IN_REVIEW").contains(status))
                throw DomainException.conflict("INVALID_STATE", "Отзыв после решения недоступен");
            if (repository.move(id, status, "WITHDRAWN", expectedVersion, null, null, LocalDateTime.now()) != 1)
                throw DomainException.conflict("STATE_CHANGED", "Заявка изменилась. Обновите карточку");
            audit(id, user.key(), status, "WITHDRAWN", "WITHDRAWN", "Клиент отозвал заявку");
            return detail(user, id);
        });
    }

    public Map<String, Object> accept(
        User user,
        String id,
        Map<String, Object> body,
        String key
    ) {
        user.client();
        expire(user, id);
        return unitOfWork.required(() -> {
            String offerId = Util.required(body, "offer_id");
            int version = Util.number(body.get("offer_version"), 1, Integer.MAX_VALUE, "offer_version");
            String hash = Util.hash(id + "|" + offerId + "|" + version);
            String old = existing(user, "ACCEPT", key, hash);
            if (old != null)
                return detail(user, old);
            Map<String, Object> a = raw(user, id);
            if (!List.of("APPROVED", "COUNTEROFFER").contains(a.get("status")))
                throw DomainException.conflict("INVALID_STATE", "Предложение недоступно");
            Map<String, Object> offer = latestOffer(id);
            if (!offerId.equals(offer.get("id")) || version != number(offer.get("version")))
                throw DomainException.conflict("OFFER_STALE", "Предложение изменилось. Обновите заявку");
            if (!((LocalDateTime) offer.get("expires_at")).isAfter(LocalDateTime.now()))
                throw DomainException.conflict("OFFER_EXPIRED", "Срок предложения истёк");
            if (repository.move(id, (String) a.get("status"), "OFFER_ACCEPTED", number(a.get("version")),
                null, null, LocalDateTime.now()) != 1)
                throw DomainException.conflict("STATE_CHANGED", "Ответ уже записан. Обновите карточку");
            repository.insertResponse(id, offerId, version, "ACCEPTED", LocalDateTime.now());
            repository.insertContract(id, offerId, version, "CR-" + id.substring(0, 8).toUpperCase(),
                LocalDateTime.now());
            audit(id, user.key(), (String) a.get("status"), "OFFER_ACCEPTED", "OFFER_ACCEPTED",
                "Принята версия " + version);
            storeKey(user, "ACCEPT", key, hash, id);
            return detail(user, id);
        });
    }

    public Map<String, Object> decline(
        User user,
        String id
    ) {
        user.client();
        expire(user, id);
        return unitOfWork.required(() -> {
            Map<String, Object> a = raw(user, id);
            if (!List.of("APPROVED", "COUNTEROFFER").contains(a.get("status")))
                throw DomainException.conflict("INVALID_STATE", "Предложение недоступно");
            Map<String, Object> offer = latestOffer(id);
            if (repository.move(id, (String) a.get("status"), "CLOSED", number(a.get("version")),
                null, "CLIENT_DECLINED", LocalDateTime.now()) != 1)
                throw DomainException.conflict("STATE_CHANGED", "Ответ уже записан. Обновите карточку");
            repository.insertResponse(id, (String) offer.get("id"), number(offer.get("version")),
                "DECLINED", LocalDateTime.now());
            audit(id, user.key(), (String) a.get("status"), "CLOSED", "OFFER_DECLINED",
                "Клиент отказался от предложения");
            return detail(user, id);
        });
    }

    public Map<String, Object> sign(
        User user,
        String id,
        Map<String, Object> body,
        String key
    ) {
        user.client();
        unitOfWork.required(() -> {
            String hash = Util.hash(id + "|" + body.get("confirmation"));
            String old = existing(user, "SIGN", key, hash);
            if (old != null)
                return old;
            Map<String, Object> a = raw(user, id);
            if (!"OFFER_ACCEPTED".equals(a.get("status")))
                throw DomainException.conflict("INVALID_STATE", "Сначала примите предложение");
            if (!Boolean.TRUE.equals(body.get("confirmation")))
                throw DomainException.bad("CONFIRMATION_REQUIRED", "Подтвердите подписание");
            var contracts = repository.contracts(id);
            if (contracts.isEmpty() || repository.responses(id).isEmpty())
                throw DomainException.conflict("CONTRACT_NOT_READY", "Договор не соответствует принятому предложению");
            Map<String, Object> contract = contracts.getFirst(), response = repository.responses(id).getFirst();
            if (!Objects.equals(contract.get("offer_id"), response.get("offer_id"))
                || !Objects.equals(contract.get("offer_version"), response.get("offer_version")))
                throw DomainException.conflict("OFFER_STALE", "Условия договора изменились");
            if (repository.move(id, "OFFER_ACCEPTED", "SIGNED", number(a.get("version")), null, null,
                LocalDateTime.now()) != 1)
                throw DomainException.conflict("STATE_CHANGED", "Состояние заявки изменилось");
            repository.signContract(id, LocalDateTime.now());
            audit(id, user.key(), "OFFER_ACCEPTED", "SIGNED", "CONTRACT_SIGNED", "Договор подписан");
            storeKey(user, "SIGN", key, hash, id);
            return id;
        });
        // Separate transaction: a failed credit leaves a signed contract recoverable.
        try {
            disburse(user, id, "SIGN-" + id);
        } catch (RuntimeException ex) {
            return detail(user, id);
        }
        return detail(user, id);
    }

    public Map<String, Object> disburse(
        User user,
        String id,
        String key
    ) {
        return unitOfWork.required(() -> {
            user.client();
            Map<String, Object> a = raw(user, id);
            if ("DISBURSED".equals(a.get("status")))
                return detail(user, id);
            if (!"SIGNED".equals(a.get("status")))
                throw DomainException.conflict("INVALID_STATE", "Договор ещё не подписан");
            var contracts = repository.contracts(id);
            if (contracts.isEmpty() || contracts.getFirst().get("signed_at") == null)
                throw DomainException.conflict("UNSIGNED_CONTRACT", "Договор ещё не подписан");
            Map<String, Object> offer = latestOffer(id);
            if (!Objects.equals(contracts.getFirst().get("offer_id"), offer.get("id")))
                throw DomainException.conflict("OFFER_STALE", "Договор не соответствует предложению");
            Product product = products.get((String) offer.get("product_id"));
            BigDecimal amount = (BigDecimal) offer.get("amount");
            Integer months = (Integer) offer.get("term_months"), days = (Integer) offer.get("duration_days");
            Map<String, Object> calc = products.calculate(product, amount, months, days);
            String loanId = Util.id();
            List<?> schedule = (List<?>) calc.get("schedule");
            Map<?, ?> first = (Map<?, ?>) schedule.getFirst();
            LocalDate firstDue = days == null ? today.plusMonths(1) : today.plusDays(days);
            repository.insertLoan(loanId, id, user.company(), amount, amount, firstDue,
                (BigDecimal) first.get("payment"), LocalDateTime.now());
            for (Object row : schedule) {
                Map<?, ?> period = (Map<?, ?>) row;
                int installment = number(period.get("period"));
                LocalDate due = days == null ? today.plusMonths(installment) : today.plusDays(days);
                repository.insertSchedule(loanId, installment, due, (BigDecimal) period.get("principal"),
                    (BigDecimal) period.get("interest"), (BigDecimal) period.get("payment"));
            }
            repository.insertLedgerEvent(Util.id(), user.company(), today, amount, "DISBURSEMENT",
                "Выдача кредита " + loanId);
            repository.creditCompany(amount, user.company());
            if (repository.move(id, "SIGNED", "DISBURSED", number(a.get("version")), null, null,
                LocalDateTime.now()) != 1)
                throw DomainException.conflict("STATE_CHANGED", "Выдача уже выполнена");
            audit(id, "SYSTEM", "SIGNED", "DISBURSED", "DISBURSED", "Средства зачислены");
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
        var contracts = repository.contracts(id);
        if (contracts.isEmpty())
            throw DomainException.conflict("CONTRACT_NOT_READY", "Договор ещё не готов");
        Map<String, Object> result = new LinkedHashMap<>(contracts.getFirst());
        result.put("offer", latestOffer(id));
        result.put("company_name", repository.company((String) a.get("company_id")).get("name"));
        return result;
    }

    public Map<String, Object> copyToDraft(
        User user,
        String id
    ) {
        user.client();
        raw(user, id);
        Map<String, Object> request = repository.snapshot(id);
        Map<String, Object> draft = drafts.create(user);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expected_version", draft.get("version"));
        body.put("purpose", request.get("purpose"));
        body.put("amount", request.get("amount"));
        body.put("term_months", request.get("term_months"));
        body.put("duration_days", request.get("duration_days"));
        LocalDate need = (LocalDate) request.get("needed_by");
        body.put("needed_by", need != null && need.isAfter(today) ? need : today.plusDays(10));
        body.put("product_id", request.get("product_id"));
        body.put("goal_text", request.get("goal_text"));
        return drafts.update(user, (String) draft.get("id"), body);
    }

    public List<Map<String, Object>> notifications(User user) {
        user.client();
        return repository.notifications(user.company());
    }

    public List<Map<String, Object>> readNotification(
        User user,
        String id
    ) {
        user.client();
        repository.readNotification(id, user.company(), LocalDateTime.now());
        return notifications(user);
    }

    public List<Map<String, Object>> loans(User user) {
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
        Map<String, Object> result = new LinkedHashMap<>(rows.getFirst());
        result.put("schedule", repository.schedule(id));
        return result;
    }

    private void assigned(
        User user,
        Map<String, Object> a
    ) {
        if (!user.key().equals(a.get("assigned_to")))
            throw DomainException.forbidden("NOT_ASSIGNEE", "Решение может принять ответственный сотрудник");
    }

    private Map<String, Object> latestOffer(String id) {
        var rows = repository.offers(id);
        if (rows.isEmpty())
            throw DomainException.conflict("OFFER_MISSING", "Предложение не найдено");
        return rows.getFirst();
    }

    private void expire(
        User user,
        String id
    ) {
        Map<String, Object> a = raw(user, id);
        if (!List.of("APPROVED", "COUNTEROFFER").contains(a.get("status")))
            return;
        var offers = repository.offers(id);
        if (offers.isEmpty() || ((LocalDateTime) offers.getFirst().get("expires_at")).isAfter(LocalDateTime.now()))
            return;
        unitOfWork.required(() -> {
            if (repository.move(id, (String) a.get("status"), "EXPIRED", number(a.get("version")),
                null, "OFFER_EXPIRED", LocalDateTime.now()) == 1)
                audit(id, "SYSTEM", (String) a.get("status"), "EXPIRED", "OFFER_EXPIRED",
                    "Срок предложения истёк");
            return null;
        });
    }

    private Map<String, Object> report(String id) {
        var rows = repository.reports(id);
        return rows.isEmpty() ? null : json.read((String) rows.getFirst().get("report_json"));
    }

    private String riskCategory(Map<String, Object> report) {
        if (report == null || report.get("adjusted_score") == null)
            return null;
        if ("REJECTED".equals(report.get("outcome")))
            return "HIGH";
        int value = number(report.get("adjusted_score"));
        return value >= 75 ? "LOW" : value >= 45 ? "MEDIUM" : "HIGH";
    }

    private String meaningful(
        Map<String, Object> body,
        String field,
        int min
    ) {
        String value = Util.required(body, field);
        if (value.replaceAll("\\s+", "").length() < min)
            throw DomainException.bad("INSUFFICIENT_" + field.toUpperCase(),
                "Добавьте содержательный текст: " + switch (field) {
                    case "internal_reason" -> "внутреннее обоснование";
                    case "client_message" -> "сообщение клиенту";
                    case "divergence_reason" -> "расхождение со скорингом";
                    default -> "причина решения";
                });
        int max = "client_message".equals(field) ? 1000 : 2000;
        if (value.length() > max)
            throw DomainException.bad("FIELD_TOO_LONG", "Сократите текст до " + max + " символов");
        return value;
    }

    private int number(Object value) {
        return ((Number) value).intValue();
    }

    private void audit(
        String id,
        String actor,
        String old,
        String next,
        String type,
        String details
    ) {
        repository.insertAuditEvent(Util.id(), id, actor, old, next, type, details, LocalDateTime.now());
    }

    private void transition(
        String id,
        String actor,
        String old,
        String next,
        String reason
    ) {
        if (repository.transition(next, reason, LocalDateTime.now(), id, old) != 1)
            throw DomainException.conflict("STATE_CHANGED", "Заявка изменилась. Обновите данные");
        audit(id, actor, old, next, next, reason);
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
