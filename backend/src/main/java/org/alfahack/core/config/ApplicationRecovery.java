package org.alfahack.core.config;

import org.alfahack.core.domain.User;
import org.alfahack.features.applications.port.ApplicationUseCases;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Retry a signed but not yet credited application after process restart. */
@Component
@Order(2)
public class ApplicationRecovery implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(ApplicationRecovery.class);
    private final JdbcTemplate db;
    private final ApplicationUseCases applications;

    public ApplicationRecovery(
        JdbcTemplate db,
        ApplicationUseCases applications
    ) {
        this.db = db;
        this.applications = applications;
    }

    @Override
    public void run(String... args) {
        for (var row : db
            .queryForList("SELECT a.id FROM applications a LEFT JOIN scoring_reports s ON s.application_id=a.id "
                + "WHERE a.status IN ('SUBMITTED','CHECKING') OR (a.status='AWAITING_DECISION' "
                + "AND a.demo_profile IS NOT NULL AND s.assessed_at IS NULL)")) {
            String id = (String) row.get("id");
            try {
                applications.check(new User("SYSTEM", "STAFF", null, ""), id, null);
            } catch (RuntimeException ex) {
                log.warn("Application {} still needs scoring", id, ex);
            }
        }
        for (var row : db.queryForList("SELECT id,company_id FROM applications WHERE status='SIGNED'")) {
            String id = (String) row.get("id"), company = (String) row.get("company_id");
            try {
                applications.disburse(new User("SYSTEM", "CLIENT", company, ""), id, "RECOVERY-" + id);
            } catch (RuntimeException ex) {
                log.warn("Application {} remains signed; disbursement can be retried", id, ex);
            }
        }
    }
}
