package org.alfahack.core.config;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.alfahack.core.domain.Util;
import org.alfahack.features.auth.port.AuthUseCases;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;

@Component
@Order(0)
public class SeedData implements CommandLineRunner {

    private final JdbcTemplate db;
    private final LocalDate today;
    private final AuthUseCases auth;

    public SeedData(
        JdbcTemplate db,
        AuthUseCases auth,
        @Value("${app.demo-today}") String today
    ) {
        this.db = db;
        this.auth = auth;
        this.today = LocalDate.parse(today);
    }

    @Override
    public void run(
        String... args
    ) {
        if (db.queryForObject("SELECT COUNT(*) FROM companies", Integer.class) > 0) {
            seedCredentials();
            return;
        }
        db.update("INSERT INTO companies(id,name,balance) VALUES (?,?,?)", "STABLE", "Кофейня Вторая чашка",
            new BigDecimal("800000.00"));
        db.update("INSERT INTO companies(id,name,balance) VALUES (?,?,?)", "GAP", "Мастерская Горизонт",
            new BigDecimal("480000.00"));
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
        for (String[] demo : new String[][]{
                {"REJECT", "reject", "Стройка Север"}, {"MISSING", "missing", "Магазин Новый день"},
                {"COUNTER", "counter", "Кафе У дома"}
        }) {
            if (db.queryForObject("SELECT COUNT(*) FROM companies WHERE id=?", Integer.class, demo[0]) == 0) {
                db.update("INSERT INTO companies(id,name,balance) VALUES (?,?,?)", demo[0], demo[2],
                    new BigDecimal("800000"));
                db.update("INSERT INTO demo_accounts VALUES (?,?,?,?)", demo[1], "CLIENT", demo[0], demo[2]);
            }
            auth.ensureCredential(demo[1], "Alfa2026!");
        }
        db.update("UPDATE demo_accounts SET label='Кофейня Вторая чашка' WHERE account_key='stable'");
        db.update("UPDATE demo_accounts SET label='Мастерская Горизонт' WHERE account_key='gap'");
        db.update("UPDATE demo_accounts SET label='Кредитный специалист' WHERE account_key='staff'");
        auth.ensureCredential("stable", "Alfa2026!");
        auth.ensureCredential("gap", "Alfa2026!");
        auth.ensureCredential("staff", "Alfa2026!");
    }

    private void event(
        String company,
        LocalDate date,
        String amount,
        String kind,
        String source
    ) {
        db.update("INSERT INTO ledger_events VALUES (?,?,?,?,?,?)", Util.id(), company, date, new BigDecimal(amount),
            kind, source);
    }
}
