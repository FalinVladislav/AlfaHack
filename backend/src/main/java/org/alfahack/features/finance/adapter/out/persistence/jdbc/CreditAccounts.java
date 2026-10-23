package org.alfahack.features.finance.adapter.out.persistence.jdbc;

import static org.alfahack.core.adapter.out.persistence.jdbc.JdbcRows.row;
import static org.alfahack.core.adapter.out.persistence.jdbc.JdbcRows.rows;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.alfahack.core.domain.User;
import org.alfahack.core.errors.DomainException;
import org.alfahack.features.finance.port.CreditAccountUseCases;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreditAccounts implements CreditAccountUseCases {

    private final JdbcTemplate db;
    private final LocalDate today;

    public CreditAccounts(
        JdbcTemplate db,
        @Value("${app.demo-today}") String today
    ) {
        this.db = db;
        this.today = LocalDate.parse(today);
    }

    public Map<String, Object> load(
        User user,
        String search,
        String source,
        String from,
        String to,
        int loanPage,
        int paymentPage,
        int size
    ) {
        user.client();
        if (!List.of("ALL", "EXISTING", "ISSUED").contains(source))
            throw DomainException.bad("INVALID_SOURCE", "Неизвестный тип кредита");
        if (search.length() > 100)
            throw DomainException.bad("INVALID_SEARCH", "Слишком длинный запрос");
        LocalDate dueFrom, dueTo;
        try {
            dueFrom = from.isBlank() ? null : LocalDate.parse(from);
            dueTo = to.isBlank() ? null : LocalDate.parse(to);
        } catch (RuntimeException exception) {
            throw DomainException.bad("INVALID_PERIOD", "Проверьте даты списаний");
        }
        if (dueFrom != null && dueTo != null && dueFrom.isAfter(dueTo))
            throw DomainException.bad("INVALID_PERIOD", "Начало периода позже окончания");
        int limit = Math.max(1, Math.min(50, size));
        int lp = Math.max(1, loanPage), pp = Math.max(1, paymentPage);
        String cte = "WITH credit_rows AS ("
            + "SELECT e.id,e.company_id,e.name,e.remaining_debt,LEAST(e.monthly_payment,e.remaining_debt+ROUND(e.remaining_debt*e.rate/12,2)) AS monthly_payment,e.rate,e.next_payment_date,e.end_date,'EXISTING' AS source,CAST(NULL AS INTEGER) AS term_months,CAST(NULL AS INTEGER) AS duration_days FROM existing_loans e "
            + "UNION ALL SELECT l.id,l.company_id,COALESCE(o.product_id,a.product_id) AS name,l.balance AS remaining_debt,l.next_payment_amount AS monthly_payment,COALESCE(o.annual_rate,a.annual_rate) AS rate,l.next_payment_date,CAST(NULL AS DATE) AS end_date,'ISSUED' AS source,COALESCE(o.term_months,a.term_months) AS term_months,COALESCE(o.duration_days,a.duration_days) AS duration_days FROM loans l JOIN applications a ON a.id=l.application_id LEFT JOIN bank_decisions d ON d.application_id=a.id LEFT JOIN application_offers o ON o.id=d.offer_id) ";
        String base = " FROM credit_rows c LEFT JOIN loan_names n ON n.company_id=c.company_id AND n.source=c.source AND n.loan_id=c.id WHERE c.company_id=? AND c.remaining_debt>0";
        List<Object> common = new ArrayList<>();
        common.add(user.company());
        if (!"ALL".equals(source)) {
            base += " AND c.source=?";
            common.add(source);
        }
        if (!search.isBlank()) {
            base += " AND (POSITION(? IN LOWER(COALESCE(n.display_name,c.name)))>0 OR POSITION(? IN LOWER(c.id))>0)";
            common.add(search.trim().toLowerCase());
            common.add(search.trim().toLowerCase());
        }
        String paymentsWhere = base;
        List<Object> paymentArgs = new ArrayList<>(common);
        if (dueFrom != null) {
            paymentsWhere += " AND c.next_payment_date>=?";
            paymentArgs.add(dueFrom);
        }
        if (dueTo != null) {
            paymentsWhere += " AND c.next_payment_date<=?";
            paymentArgs.add(dueTo);
        }
        long loanTotal = db.queryForObject(cte + "SELECT COUNT(*)" + base, Long.class, common.toArray());
        long paymentTotal = db.queryForObject(cte + "SELECT COUNT(*)" + paymentsWhere, Long.class,
            paymentArgs.toArray());
        String columns = "SELECT c.*,n.display_name";
        List<Object> loansArgs = new ArrayList<>(common);
        loansArgs.add(limit);
        loansArgs.add((long) (lp - 1) * limit);
        List<Object> paymentsArgs = new ArrayList<>(paymentArgs);
        paymentsArgs.add(limit);
        paymentsArgs.add((long) (pp - 1) * limit);
        var loans = rows(db.queryForList(cte + columns + base + " ORDER BY c.next_payment_date,c.id LIMIT ? OFFSET ?",
            loansArgs.toArray()));
        var payments = rows(
            db.queryForList(cte + columns + paymentsWhere + " ORDER BY c.next_payment_date,c.id LIMIT ? OFFSET ?",
                paymentsArgs.toArray()));
        Map<String, Object> summary = row(db.queryForMap(cte
            + "SELECT COALESCE(SUM(c.remaining_debt),0) AS debt,COALESCE(SUM(c.monthly_payment),0) AS monthly" + base,
            common.toArray()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("loans", Map.of("items", loans, "total", loanTotal, "page", lp, "size", limit));
        result.put("payments", Map.of("items", payments, "total", paymentTotal, "page", pp, "size", limit));
        result.put("summary", summary);
        return result;
    }

    @Transactional
    public Map<String, Object> rename(
        User user,
        String source,
        String id,
        String name
    ) {
        user.client();
        checkSource(source);
        String cleaned = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (cleaned.length() > 100)
            throw DomainException.bad("NAME_TOO_LONG", "Название должно быть короче 100 символов");
        ownedLoan(user.company(), source, id);
        if (cleaned.isEmpty())
            db.update("DELETE FROM loan_names WHERE company_id=? AND source=? AND loan_id=?", user.company(), source,
                id);
        else
            if (db.update(
                "UPDATE loan_names SET display_name=?,updated_at=? WHERE company_id=? AND source=? AND loan_id=?",
                cleaned, LocalDateTime.now(), user.company(), source, id) == 0)
                db.update(
                    "INSERT INTO loan_names(company_id,source,loan_id,display_name,updated_at) VALUES (?,?,?,?,?)",
                    user.company(), source, id, cleaned, LocalDateTime.now());
        return Map.of("source", source, "loan_id", id, "display_name", cleaned);
    }

    @Transactional
    public Map<String, Object> pay(
        User user,
        String source,
        String id,
        String requestKey,
        String expectedDue
    ) {
        user.client();
        checkSource(source);
        if (requestKey == null || requestKey.isBlank() || requestKey.length() > 120)
            throw DomainException.bad("IDEMPOTENCY_KEY_REQUIRED", "Не удалось подтвердить платёж. Повторите действие");
        var previous = rows(db.queryForList("SELECT * FROM loan_payments WHERE account_key=? AND request_key=?",
            user.key(), requestKey));
        if (!previous.isEmpty()) {
            var payment = previous.getFirst();
            if (!source.equals(payment.get("source")) || !id.equals(payment.get("loan_id"))
                || !payment.get("due_date").toString().equals(expectedDue))
                throw DomainException.conflict("KEY_REUSED", "Ключ запроса уже использован для другого платежа");
            return payment;
        }
        // The company row serializes debits across all its loans and prevents a
        // negative balance.
        var company = rows(db.queryForList("SELECT balance FROM companies WHERE id=? FOR UPDATE", user.company()));
        if (company.isEmpty())
            throw DomainException.missing();
        previous = rows(db.queryForList("SELECT * FROM loan_payments WHERE account_key=? AND request_key=?",
            user.key(), requestKey));
        if (!previous.isEmpty()) {
            var payment = previous.getFirst();
            if (!source.equals(payment.get("source")) || !id.equals(payment.get("loan_id"))
                || !payment.get("due_date").toString().equals(expectedDue))
                throw DomainException.conflict("KEY_REUSED", "Ключ запроса уже использован для другого платежа");
            return payment;
        }
        Map<String, Object> loan = ownedLoan(user.company(), source, id);
        LocalDate due = (LocalDate) loan.get("next_payment_date");
        if (expectedDue == null || !due.toString().equals(expectedDue))
            throw DomainException.conflict("PAYMENT_CHANGED", "Дата или сумма платежа изменилась. Обновите страницу");
        BigDecimal debt = (BigDecimal) loan.get("remaining_debt");
        if (debt.signum() <= 0)
            throw DomainException.conflict("LOAN_CLOSED", "Кредит уже погашен");
        BigDecimal payment, principal, interest;
        LocalDate nextDue;
        BigDecimal nextAmount;
        if ("ISSUED".equals(source)) {
            var schedule = rows(db.queryForList(
                "SELECT installment,due_date,principal,interest,payment FROM loan_schedule WHERE loan_id=? AND paid_at IS NULL ORDER BY installment LIMIT 1",
                id));
            if (schedule.isEmpty() || !due.equals(schedule.getFirst().get("due_date")))
                throw DomainException.conflict("PAYMENT_CHANGED", "График платежей изменился. Обновите страницу");
            var row = schedule.getFirst();
            principal = (BigDecimal) row.get("principal");
            interest = (BigDecimal) row.get("interest");
            payment = (BigDecimal) row.get("payment");
            db.update("UPDATE loan_schedule SET paid_at=? WHERE loan_id=? AND installment=? AND paid_at IS NULL",
                LocalDateTime.now(), id, row.get("installment"));
            var following = rows(db.queryForList(
                "SELECT due_date,payment FROM loan_schedule WHERE loan_id=? AND paid_at IS NULL ORDER BY installment LIMIT 1",
                id));
            nextDue = following.isEmpty() ? due : (LocalDate) following.getFirst().get("due_date");
            nextAmount = following.isEmpty() ? BigDecimal.ZERO : (BigDecimal) following.getFirst().get("payment");
            db.update(
                "UPDATE loans SET balance=?,next_payment_date=?,next_payment_amount=? WHERE id=? AND company_id=?",
                debt.subtract(principal).max(BigDecimal.ZERO), nextDue, nextAmount, id, user.company());
        } else {
            interest = debt.multiply((BigDecimal) loan.get("rate")).divide(BigDecimal.valueOf(12), 2,
                RoundingMode.HALF_UP);
            payment = ((BigDecimal) loan.get("monthly_payment")).min(debt.add(interest));
            principal = payment.subtract(interest);
            if (principal.signum() <= 0)
                throw DomainException.conflict("INVALID_SCHEDULE", "Платёж не уменьшает долг");
            nextDue = due.plusMonths(1);
            nextAmount = payment;
            db.update("UPDATE existing_loans SET remaining_debt=?,next_payment_date=? WHERE id=? AND company_id=?",
                debt.subtract(principal).max(BigDecimal.ZERO), nextDue, id, user.company());
        }
        BigDecimal balance = (BigDecimal) company.getFirst().get("balance");
        if (balance.compareTo(payment) < 0)
            throw DomainException.conflict("INSUFFICIENT_FUNDS", "На счёте недостаточно средств для платежа");
        String paymentId = UUID.randomUUID().toString();
        db.update("UPDATE companies SET balance=? WHERE id=?", balance.subtract(payment), user.company());
        db.update(
            "INSERT INTO loan_payments(id,company_id,account_key,request_key,source,loan_id,due_date,payment,principal,interest,paid_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
            paymentId, user.company(), user.key(), requestKey, source, id, due, payment, principal, interest,
            LocalDateTime.now());
        db.update("INSERT INTO ledger_events(id,company_id,event_date,amount,kind,source) VALUES (?,?,?,?,?,?)",
            paymentId, user.company(), today, payment.negate(), "LOAN_PAYMENT", "Платёж по кредиту " + id);
        db.update(
            "INSERT INTO transactions(id,company_id,date,amount,type,category,counterparty_id) VALUES (?,?,?,?,?,?,?)",
            paymentId, user.company(), today, payment, "EXPENSE", "Платёж по кредиту", "Банк");
        if (db.update("UPDATE balances SET balance=? WHERE company_id=? AND date=?", balance.subtract(payment),
            user.company(), today) == 0)
            db.update("INSERT INTO balances(company_id,date,balance) VALUES (?,?,?)", user.company(), today,
                balance.subtract(payment));
        return row(db.queryForMap("SELECT * FROM loan_payments WHERE id=?", paymentId));
    }

    private void checkSource(String source) {
        if (!List.of("EXISTING", "ISSUED").contains(source))
            throw DomainException.bad("INVALID_SOURCE", "Неизвестный тип кредита");
    }

    private Map<String, Object> ownedLoan(
        String company,
        String source,
        String id
    ) {
        String table = "ISSUED".equals(source) ? "loans" : "existing_loans";
        String debt = "ISSUED".equals(source) ? "balance" : "remaining_debt";
        var rows = rows(db.queryForList("SELECT " + debt + " AS remaining_debt,next_payment_date"
            + ("EXISTING".equals(source) ? ",monthly_payment,rate" : "")
            + " FROM " + table + " WHERE id=? AND company_id=? FOR UPDATE", id, company));
        if (rows.isEmpty())
            throw DomainException.missing();
        return rows.getFirst();
    }
}
