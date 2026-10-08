package com.offerpilot;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/** 独立数据库验证从已共享 V11 升级，不读取开发数据库。 */
@Testcontainers
class AuthMigrationIntegrationTest {
    @Container
    static PostgreSQLContainer<?> database = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @Test
    void upgradeKeepsRotatedAccountsAndDisablesUnchangedPublicCredentialsWithoutDeletingData() {
        var configuration = Flyway.configure().dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
        configuration.target("11").load().migrate();
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword()));
        jdbc.update("UPDATE users SET password_hash='rotated-private-hash',nickname='Preserve me' WHERE id='usr_test_user001'");
        configuration.target("latest").load().migrate();
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id='usr_test_user001'", String.class)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT nickname FROM users WHERE id='usr_test_user001'", String.class)).isEqualTo("Preserve me");
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id='usr_test_staff001'", String.class)).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id IN ('usr_test_user001','usr_test_staff001')", Integer.class)).isEqualTo(2);
    }
}
