package org.alfahack;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class SeedData implements CommandLineRunner {
    private final JdbcTemplate db;
    private final LocalDate today;
    private final AuthService auth;
    public SeedData(JdbcTemplate db, AuthService auth, @Value("${app.demo-today}") String today) { this.db=db; this.auth=auth; this.today=LocalDate.parse(today); }
    @Override public void run(String... args) {
        if (db.queryForObject("SELECT COUNT(*) FROM companies", Integer.class) > 0) { seedCredentials(); return; }
        db.update("INSERT INTO companies VALUES (?,?,?)", "STABLE", "Кофейня Вторая чашка", new BigDecimal("800000.00"));
        db.update("INSERT INTO companies VALUES (?,?,?)", "GAP", "Мастерская Горизонт", new BigDecimal("480000.00"));
        db.update("INSERT INTO demo_accounts VALUES (?,?,?,?)", "stable", "CLIENT", "STABLE", "Кофейня Вторая чашка");
        db.update("INSERT INTO demo_accounts VALUES (?,?,?,?)", "gap", "CLIENT", "GAP", "Мастерская Горизонт");
        db.update("INSERT INTO demo_accounts VALUES (?,?,?,?)", "staff", "STAFF", null, "Кредитный специалист");
        event("GAP", today.plusDays(20), "100000.00", "EXPECTED", "Оплата клиента");
        event("GAP", today.plusDays(25), "-700000.00", "SCHEDULED", "Оплата поставщику");
        event("GAP", today.plusDays(28), "200000.00", "EXPECTED", "Оплата клиента");
        event("STABLE", today.plusDays(12), "400000.00", "EXPECTED", "Выручка");
        event("STABLE", today.plusDays(17), "-250000.00", "SCHEDULED", "Аренда и поставки");
        seedCredentials();
    }
    private void seedCredentials() {
        db.update("UPDATE demo_accounts SET label='Кофейня Вторая чашка' WHERE account_key='stable'");
        db.update("UPDATE demo_accounts SET label='Мастерская Горизонт' WHERE account_key='gap'");
        db.update("UPDATE demo_accounts SET label='Кредитный специалист' WHERE account_key='staff'");
        auth.ensureCredential("stable","Alfa2026!");auth.ensureCredential("gap","Alfa2026!");auth.ensureCredential("staff","Alfa2026!");
    }
    private void event(String company, LocalDate date, String amount, String kind, String source) {
        db.update("INSERT INTO ledger_events VALUES (?,?,?,?,?,?)", Util.id(), company, date, new BigDecimal(amount), kind, source);
    }
}
