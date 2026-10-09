package com.offerpilot.auth.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.id.IdGenerator;
import com.offerpilot.config.OfferPilotProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Environment-provisioned fictional USER; never promotes an existing real account. */
@Component
@Order(10)
public class DemoAccountBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoAccountBootstrap.class);
    private final OfferPilotProperties.Demo config;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final TransactionTemplate transaction;

    public DemoAccountBootstrap(OfferPilotProperties properties, JdbcTemplate jdbc,
                                PasswordEncoder passwords, PlatformTransactionManager transactions) {
        config = properties.demo();
        this.jdbc = jdbc;
        this.passwords = passwords;
        transaction = new TransactionTemplate(transactions);
    }

    public boolean enabled() { return config != null && config.enabled(); }

    @Override
    public void run(ApplicationArguments args) {
        ensure();
    }

    /** Also used by on-demand login/reset. Null means disabled or unsafe configuration. */
    public String ensure() {
        if (!enabled()) return null;
        String email;
        try {
            email = PasswordLoginService.normalizeRegistrationEmail(config.email());
            PasswordLoginService.validateRegistrationPassword(config.password());
        } catch (BusinessException ex) {
            log.warn("Demo bootstrap skipped: set a valid DEMO_ACCOUNT_EMAIL and DEMO_ACCOUNT_PASSWORD");
            return null;
        }
        // Hash outside the database lock. Rotation applies on every boot and explicit ensure.
        String hash = passwords.encode(config.password());
        return transaction.execute(status -> {
            jdbc.execute("LOCK TABLE users IN SHARE ROW EXCLUSIVE MODE");
            var byEmail = jdbc.queryForList("SELECT id, is_demo, ('USER' = ANY(roles) AND NOT ('STAFF' = ANY(roles))) AS demo_role FROM users WHERE email = CAST(? AS citext)", email);
            if (!byEmail.isEmpty() && (!Boolean.TRUE.equals(byEmail.get(0).get("is_demo"))
                    || !Boolean.TRUE.equals(byEmail.get(0).get("demo_role")))) {
                log.warn("Demo bootstrap skipped: email belongs to an account without the demo USER role: {}", email);
                return null;
            }
            var demos = jdbc.queryForList("SELECT id FROM users WHERE is_demo = true AND 'USER' = ANY(roles) AND NOT ('STAFF' = ANY(roles)) ORDER BY created_at, id", String.class);
            if (byEmail.isEmpty() && demos.size() > 1) {
                log.warn("Demo bootstrap skipped: multiple demo accounts; configure an existing demo email");
                return null;
            }
            String id = !byEmail.isEmpty() ? byEmail.get(0).get("id").toString()
                    : demos.isEmpty() ? IdGenerator.next("usr") : demos.get(0);
            if (byEmail.isEmpty() && demos.isEmpty()) {
                jdbc.update("""
                        INSERT INTO users(id,email,password_hash,nickname,roles,status,is_demo)
                        VALUES (?,CAST(? AS citext),?,'体验账号','{USER}','ACTIVE',true)
                        """, id, email, hash);
                log.info("Demo bootstrap created account: {}", email);
            } else {
                // Preserve disabled status: staff suspension must not be undone by public login.
                jdbc.update("UPDATE users SET email=CAST(? AS citext), password_hash=? WHERE id=? AND is_demo=true", email, hash, id);
            }
            jdbc.update("INSERT INTO user_preferences(user_id,default_model_tier) VALUES (?,'FLASH') ON CONFLICT DO NOTHING", id);
            return id;
        });
    }

    public String requireAccount() {
        String id = ensure();
        if (id == null) throw new BusinessException(ErrorCode.DEMO_NOT_ALLOWED, "体验账号未启用");
        return id;
    }

    public PasswordLoginService.LoginResponse login(PasswordLoginService logins) {
        requireAccount();
        return logins.login(config.email().trim(), config.password());
    }
}
