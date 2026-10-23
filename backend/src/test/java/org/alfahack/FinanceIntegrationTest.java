package org.alfahack;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.alfahack.core.config.SyntheticFinanceData;
import org.alfahack.core.domain.User;
import org.alfahack.core.errors.DomainException;
import org.alfahack.features.finance.port.FinanceUseCases;
import org.alfahack.features.drafts.port.DraftUseCases;
import org.alfahack.features.applications.port.ApplicationUseCases;
import org.springframework.transaction.annotation.Transactional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:financetest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa", "spring.datasource.password=", "app.demo-today=2026-10-21"
})
class FinanceIntegrationTest {

    @Autowired
    FinanceUseCases finance;
    @Autowired
    SyntheticFinanceData seed;
    @Autowired
    JdbcTemplate db;

    @Autowired
    DraftUseCases drafts;

    @Autowired
    ApplicationUseCases applications;

    @Test
    @Transactional
    void issuedLoansIncludeMaturityDatesAndAppearAlongsideExistingDebt() {
        var draft = drafts.create(stable);
        String draftId = (String) draft.get("id");
        draft = drafts.update(stable, draftId, Map.of("expected_version", draft.get("version"),
            "purpose", "EXPANSION", "amount", "1000000", "term_months", 24, "needed_by", "2026-11-01"));
        var quotes = drafts.quote(stable, draftId, Map.of("expected_version", draft.get("version")));
        var quote = list(quotes, "options").stream()
            .filter(option -> "DEVELOPMENT".equals(option.get("product_id"))).findFirst().orElseThrow();
        var application = applications.submit(stable, Map.of("draft_id", draftId, "quote_id", quote.get("quote_id"),
            "expected_version", draft.get("version"), "consents", true), "finance-submit");
        String applicationId = (String) application.get("id");
        assertEquals("AWAITING_DECISION", application.get("status"));
        User staff = new User("staff", "STAFF", null, "test");
        var claimed = applications.claim(staff, applicationId, ((Number) application.get("version")).intValue());
        var preview = applications.preview(staff, applicationId, Map.of());
        var approved = applications.decide(staff, applicationId, Map.of(
            "expected_version", claimed.get("version"), "decision_type", "APPROVE",
            "calculation_token", preview.get("calculation_token"), "internal_reason",
            "Проверены финансовые показатели и нагрузка", "client_message",
            "Банк одобрил финансирование на указанных условиях", "expires_at",
            java.time.LocalDateTime.now().plusDays(7).toString()));
        assertEquals("APPROVED", approved.get("status"));
        var offer = (Map<?, ?>) approved.get("offer");
        applications.accept(stable, applicationId, Map.of("offer_id", offer.get("id"),
            "offer_version", offer.get("version")),
            "finance-accept");
        applications.sign(stable, applicationId, Map.of("confirmation", true), "finance-sign");
        var loans = finance.loans(stable);
        assertEquals(3, loans.size());
        var issued = loans.stream().filter(loan -> "ISSUED".equals(loan.get("source"))).findFirst().orElseThrow();
        assertEquals(LocalDate.of(2028, 10, 21), issued.get("end_date"));
        assertEquals(LocalDate.of(2026, 11, 21), issued.get("next_payment_date"));
    }
    private final User stable = new User("stable", "CLIENT", "STABLE", "test");

    @Test
    void fixturesFillAllRequestedTablesAndMonthlyTotalsMatchTransactions() {
        var overview = finance.overview(stable);
        assertEquals("SYNTHETIC", overview.get("data_origin"));
        assertEquals(30, list(overview, "transactions").size());
        assertEquals(6, list(overview, "months").size());
        assertEquals(5, list(overview, "products").size());
        assertEquals(2, list(overview, "needs").size());
        assertEquals(2, list(overview, "loans").size());
        for (var month : list(overview, "months")) {
            BigDecimal income = BigDecimal.ZERO;
            BigDecimal expenses = BigDecimal.ZERO;
            for (var transaction : list(overview, "transactions")) {
                if (!transaction.get("date").toString().startsWith(month.get("month").toString()))
                    continue;
                if ("INCOME".equals(transaction.get("type")))
                    income = income.add((BigDecimal) transaction.get("amount"));
                else
                    expenses = expenses.add((BigDecimal) transaction.get("amount"));
            }
            assertEquals(0, income.compareTo((BigDecimal) month.get("income")));
            assertEquals(0, expenses.compareTo((BigDecimal) month.get("expenses")));
        }
    }

    @Test
    void historicalBalancesReconcileWithTheSameOperations() {
        var overview = finance.overview(stable);
        var balances = list(overview, "balances");
        for (int index = 1; index < balances.size(); index++) {
            var previous = (BigDecimal) balances.get(index - 1).get("balance");
            var today = balances.get(index);
            BigDecimal movement = BigDecimal.ZERO;
            for (var transaction : list(overview, "transactions")) {
                if (!today.get("date").equals(transaction.get("date")))
                    continue;
                var amount = (BigDecimal) transaction.get("amount");
                movement = "INCOME".equals(transaction.get("type")) ? movement.add(amount) : movement.subtract(amount);
            }
            assertEquals(0, previous.add(movement).compareTo((BigDecimal) today.get("balance")));
        }
    }

    @Test
    void financeIsCompanyScopedAndUnavailableToStaff() {
        for (var user : List.of(stable, new User("gap", "CLIENT", "GAP", "test"))) {
            var overview = finance.overview(user);
            for (var key : List.of("transactions", "loans", "needs")) {
                for (var row : list(overview, key))
                    assertEquals(user.company(), row.get("company_id"));
            }
        }
        assertEquals(DomainException.Kind.FORBIDDEN, assertThrows(DomainException.class,
            () -> finance.overview(new User("staff", "STAFF", null, "test"))).kind);
    }

    @Test
    void restartingSeedDoesNotDuplicateHistoryOrLoans() {
        int transactions = db.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class);
        int loans = db.queryForObject("SELECT COUNT(*) FROM existing_loans", Integer.class);
        seed.run();
        assertEquals(transactions, db.queryForObject("SELECT COUNT(*) FROM transactions", Integer.class));
        assertEquals(loans, db.queryForObject("SELECT COUNT(*) FROM existing_loans", Integer.class));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(
        Map<String, Object> source,
        String key
    ) {
        return (List<Map<String, Object>>) source.get(key);
    }
}
