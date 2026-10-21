package com.offerpilot.auth.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.id.IdGenerator;
import com.offerpilot.config.OfferPilotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Optional first-staff provisioning; existing accounts are never promoted or changed. */
@Component
public class StaffAccountBootstrap implements ApplicationRunner {
    /** V11 public test staff, disabled by V12 while its published password is unchanged; it must not block bootstrap. */
    public static final String PUBLIC_SEED_STAFF_HASH = "$2a$12$Lu9rC7PE8YSdGM16NL1Js.qNJuwG/bKQrD4izbw626GuvNJaD4bX.";
    private static final Logger log = LoggerFactory.getLogger(StaffAccountBootstrap.class);
    private final OfferPilotProperties.StaffBootstrap config;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final TransactionTemplate transaction;

    public StaffAccountBootstrap(OfferPilotProperties properties, JdbcTemplate jdbc,
                                 PasswordEncoder passwords, PlatformTransactionManager transactions) {
        this.config = properties.staffBootstrap() == null
                ? new OfferPilotProperties.StaffBootstrap("", "") : properties.staffBootstrap();
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.transaction = new TransactionTemplate(transactions);
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean hasEmail = !config.email().isBlank();
        boolean hasPassword = !config.password().isBlank();
        if (!hasEmail && !hasPassword) return;
        if (!hasEmail || !hasPassword) {
            log.warn("Staff bootstrap skipped: set both STAFF_BOOTSTRAP_EMAIL and STAFF_BOOTSTRAP_PASSWORD");
            return;
        }
        String createdEmail = transaction.execute(status -> {
            // Serialize check-and-insert across application instances, including an empty users table.
            jdbc.execute("LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE");
            if (jdbc.queryForObject("SELECT count(*) FROM users WHERE 'STAFF' = ANY(roles) AND NOT ("
                    + "id = 'usr_test_staff001' AND email = 'staff@offerpilot.local' AND status = 'DISABLED' "
                    + "AND password_hash = '" + PUBLIC_SEED_STAFF_HASH + "')", Long.class) > 0) return null;
            String email;
            try {
                email = PasswordLoginService.normalizeRegistrationEmail(config.email());
                PasswordLoginService.validateRegistrationPassword(config.password());
            } catch (BusinessException ex) {
                log.warn("Staff bootstrap skipped: invalid email or password (register validation rules apply)");
                return null;
            }
            // users.email is citext UNIQUE; cast the bind so case-insensitive lookup matches the constraint.
            if (jdbc.queryForObject(
                    "SELECT count(*) FROM users WHERE email = CAST(? AS citext)", Long.class, email) > 0) {
                log.warn("Staff bootstrap skipped: email already belongs to an existing account: {}", email);
                return null;
            }
            try {
                jdbc.update("""
                        INSERT INTO users(id, email, password_hash, nickname, roles, status)
                        VALUES (?, CAST(? AS citext), ?, ?, '{STAFF}', 'ACTIVE')
                        """, IdGenerator.next("usr"), email, passwords.encode(config.password()),
                        email.substring(0, email.indexOf('@')));
            } catch (DuplicateKeyException ex) {
                log.warn("Staff bootstrap skipped: email race or existing account: {}", email);
                return null;
            }
            return email;
        });
        if (createdEmail != null) log.info("Staff bootstrap created account: {}", createdEmail);
    }
}
