package com.offerpilot;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 只操作一次性数据库，验证 V1–V47 和历史对齐，不接触开发数据。 */
@Testcontainers
class BranchMigrationIntegrationTest {
    @Container
    static PostgreSQLContainer<?> database = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    private String newDatabase() {
        String name = "migration_" + UUID.randomUUID().toString().replace("-", "");
        jdbc(database.getJdbcUrl()).execute("CREATE DATABASE " + name);
        return database.getJdbcUrl().replace("/" + database.getDatabaseName(), "/" + name);
    }

    private JdbcTemplate jdbc(String url) {
        return new JdbcTemplate(new DriverManagerDataSource(url, database.getUsername(), database.getPassword()));
    }

    private Flyway migrations(String url, String target, boolean outOfOrder) {
        return Flyway.configure().dataSource(url, database.getUsername(), database.getPassword())
                .target(target).outOfOrder(outOfOrder).load();
    }

    private String legacy(String target) {
        String url = newDatabase();
        migrations(url, "10", false).migrate();
        Flyway.configure().dataSource(url, database.getUsername(), database.getPassword())
                .locations("classpath:legacy-feature-migrations").ignoreMigrationPatterns("*:missing")
                .target(target).load().migrate();
        jdbc(url).update("INSERT INTO users(id,email,password_hash,nickname) VALUES ('legacy-owner','legacy@example.test','private-hash','Preserve me')");
        return url;
    }

    private void align(String url) throws Exception {
        String sql = Files.readString(Path.of("..", "..", "deploy", "migrations", "align-legacy-feature-history.sql"));
        jdbc(url).execute(sql);
    }

    @Test
    void freshDatabaseAndReleasedMainUpgradeToTwentyFive() {
        String url = newDatabase();
        migrations(url, "12", false).migrate();
        migrations(url, "18", false).migrate();
        assertThat(jdbc(url).queryForObject("SELECT to_regclass('public.knowledge_notes') IS NULL", Boolean.class)).isTrue();
        migrations(url, "latest", false).migrate();
        assertThat(jdbc(url).queryForObject("SELECT to_regclass('public.knowledge_notes') IS NOT NULL AND to_regclass('public.knowledge_note_imports') IS NOT NULL", Boolean.class)).isTrue();
        assertThat(jdbc(url).queryForObject("SELECT to_regclass('public.star_stories') IS NOT NULL", Boolean.class)).isTrue();
        assertThat(migrations(url, "latest", false).validateWithResult().validationSuccessful).isTrue();
        assertThat(jdbc(url).queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(42);
        assertThat(jdbc(url).queryForList("SELECT version FROM flyway_schema_history WHERE success", String.class))
                .contains("15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30", "31", "32", "33", "34", "40", "41", "42", "43", "44", "45", "46", "47");
        assertThat(jdbc(url).queryForObject("SELECT status FROM users WHERE id='usr_test_staff001'", String.class)).isEqualTo("DISABLED");
        String fresh = newDatabase();
        migrations(fresh, "latest", false).migrate();
        assertThat(migrations(fresh, "latest", false).validateWithResult().validationSuccessful).isTrue();
    }

    @Test void upgradesV46WithoutBackfillingFakeNotificationsAndQueuesOnlyChangedTargets() {
        String url=newDatabase();migrations(url,"46",false).migrate();var db=jdbc(url);
        db.update("INSERT INTO users(id,email,password_hash,nickname) VALUES ('v47','v47@example.test','fixture','Keep me')");
        db.update("INSERT INTO career_profiles(user_id,target,skills) VALUES ('v47','Existing target','Java')");
        migrations(url,"latest",false).migrate();
        assertThat(db.queryForObject("SELECT count(*) FROM notifications",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM career_target_plans",Integer.class)).isZero();
        db.update("UPDATE career_profiles SET skills='SQL' WHERE user_id='v47'");
        assertThat(db.queryForObject("SELECT count(*) FROM career_target_plans",Integer.class)).isZero();
        db.update("UPDATE career_profiles SET target='New target' WHERE user_id='v47'");
        String revision=db.queryForObject("SELECT revision FROM career_target_plans WHERE user_id='v47'",String.class);
        db.update("UPDATE career_profiles SET target='New target' WHERE user_id='v47'");
        assertThat(db.queryForObject("SELECT revision FROM career_target_plans WHERE user_id='v47'",String.class)).isEqualTo(revision);
        db.update("UPDATE career_profiles SET city='上海' WHERE user_id='v47'");
        assertThat(db.queryForObject("SELECT revision FROM career_target_plans WHERE user_id='v47'",String.class)).isNotEqualTo(revision);
        db.update("UPDATE career_profiles SET target='' WHERE user_id='v47'");
        assertThat(db.queryForObject("SELECT count(*) FROM career_target_plans",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT skills FROM career_profiles WHERE user_id='v47'",String.class)).isEqualTo("SQL");
    }

    @Test void upgradesV45WithOwnerScopedGithubEvidenceAndCascade() {
        String url = newDatabase(); migrations(url,"45",false).migrate(); var jdbc = jdbc(url);
        jdbc.update("INSERT INTO users(id,email,password_hash,nickname) VALUES ('v46-owner','v46@example.test','fixture-only','Keep me')");
        migrations(url,"latest",false).migrate();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM github_evidence",Integer.class)).isZero();
        jdbc.update("INSERT INTO github_evidence(user_id,username,revision) VALUES ('v46-owner','fixture','revision')");
        assertThat(jdbc.queryForObject("SELECT token_ciphertext IS NULL AND snapshot IS NULL AND fetched_at IS NULL AND status='NOT_FETCHED' FROM github_evidence WHERE user_id='v46-owner'",Boolean.class)).isTrue();
        assertThatThrownBy(() -> jdbc.update("UPDATE github_evidence SET snapshot='{}'::jsonb WHERE user_id='v46-owner'"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM users WHERE id='v46-owner'");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM github_evidence",Integer.class)).isZero();
    }

    @Test void upgradesV44KeepingOccupationIdsAndRemovingOnlyUnsupportedSalaryEstimates() {
        String url=newDatabase();migrations(url,"44",false).migrate();var jdbc=jdbc(url);
        jdbc.update("INSERT INTO occupations(id,name,family,description,source,salary_range) VALUES ('before45','Keep name','test','Keep original','custom','{\"campus\":\"15-30k\",\"source\":\"自建岗位族谱估算\"}')");
        migrations(url,"latest",false).migrate();
        assertThat(jdbc.queryForObject("SELECT name FROM occupations WHERE id='before45'",String.class)).isEqualTo("Keep name");
        assertThat(jdbc.queryForObject("SELECT salary_range IS NULL AND onet_code IS NULL FROM occupations WHERE id='before45'",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM builtin_knowledge_entries",Integer.class)).isZero();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO builtin_knowledge_entries(id,kind,title,content,industry,role,source,license,notice,tsv) VALUES ('bad-question','QUESTION','bad','bad','test','test','real-candidate','none','bad',to_tsvector('simple','bad'))"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void upgradesV43WithEmptyOwnerScopedMcpPreferencesAndCascadesOnDeletion() {
        String url = newDatabase();
        migrations(url, "43", false).migrate();
        var jdbc = jdbc(url);
        jdbc.update("INSERT INTO users(id,email,password_hash,nickname) VALUES ('v44-owner','v44@example.test','fixture-only','Keep me')");
        migrations(url, "latest", false).migrate();
        assertThat(jdbc.queryForObject("SELECT nickname FROM users WHERE id='v44-owner'", String.class)).isEqualTo("Keep me");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_mcp_servers", Integer.class)).isZero();
        jdbc.update("INSERT INTO user_mcp_servers(user_id,provider,enabled) VALUES ('v44-owner','jobs',false)");
        assertThat(jdbc.queryForObject("SELECT tools='[]'::jsonb AND status='NOT_REFRESHED' AND NOT enabled FROM user_mcp_servers WHERE user_id='v44-owner'", Boolean.class)).isTrue();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO user_mcp_servers(user_id,provider) VALUES ('v44-owner','custom')"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        jdbc.update("DELETE FROM users WHERE id='v44-owner'");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_mcp_servers", Integer.class)).isZero();
    }

    @Test void foldsPersistedCheapPreferencesJobsAndReportsWithoutChangingEvidence() {
        String url = newDatabase();
        migrations(url, "17", false).migrate();
        var jdbc = jdbc(url);
        jdbc.update("INSERT INTO users(id,email,password_hash,nickname) VALUES ('tier-owner','tier-migration@example.test','fixture-only','Tier migration')");
        jdbc.update("INSERT INTO user_preferences(user_id,default_model_tier) VALUES ('tier-owner','CHEAP')");
        jdbc.update("INSERT INTO resume_model_preferences(user_id,text_tier,vision_tier) VALUES ('tier-owner','CHEAP','cheap')");
        jdbc.update("INSERT INTO files(id,user_id,purpose,bucket,object_key,name,mime,size,sha256) VALUES ('tier-file','tier-owner','RESUME','test','tier.txt','tier.txt','text/plain',10,'fixture')");
        jdbc.update("INSERT INTO resumes(id,user_id,title,source_file_id,parse_status,text_model_tier,vision_model_tier) VALUES ('tier-resume','tier-owner','Keep title','tier-file','QUEUED','CHEAP','CHEAP')");
        jdbc.update("INSERT INTO resume_versions(id,resume_id,user_id,version_no,source,structured,plain_text) VALUES ('tier-version','tier-resume','tier-owner',1,'UPLOAD','{}','Keep text')");
        jdbc.update("INSERT INTO resume_diagnoses(id,user_id,resume_id,version_id,request_id,status,model_tier,report) VALUES ('tier-diag','tier-owner','tier-resume','tier-version','tier-request','SUCCEEDED','CHEAP','{\"modelTier\":\"CHEAP\",\"model\":\"old-model\"}')");
        jdbc.update("INSERT INTO resume_optimizations(id,user_id,resume_id,version_id,request_id,status,model_tier,job_description) VALUES ('tier-opt','tier-owner','tier-resume','tier-version','tier-opt-request','FAILED','CHEAP','Keep JD')");
        migrations(url, "latest", false).migrate();
        for (String query : java.util.List.of(
                "SELECT default_model_tier FROM user_preferences WHERE user_id='tier-owner'",
                "SELECT text_tier FROM resume_model_preferences WHERE user_id='tier-owner'",
                "SELECT vision_tier FROM resume_model_preferences WHERE user_id='tier-owner'",
                "SELECT text_model_tier FROM resumes WHERE id='tier-resume'",
                "SELECT vision_model_tier FROM resumes WHERE id='tier-resume'",
                "SELECT model_tier FROM resume_diagnoses WHERE id='tier-diag'",
                "SELECT model_tier FROM resume_optimizations WHERE id='tier-opt'"))
            assertThat(jdbc.queryForObject(query, String.class)).isEqualTo("FLASH");
        assertThat(jdbc.queryForObject("SELECT report->>'model' FROM resume_diagnoses WHERE id='tier-diag'", String.class)).isEqualTo("old-model");
        assertThat(jdbc.queryForObject("SELECT job_description FROM resume_optimizations WHERE id='tier-opt'", String.class)).isEqualTo("Keep JD");
    }

    @ParameterizedTest
    @ValueSource(strings = {"11", "12", "13"})
    void legacyBranchesKeepOwnersAndBusinessDataWithoutReplayingTheirDdl(String target) throws Exception {
        String url = legacy(target);
        var jdbc = jdbc(url);
        assertThat(jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='11'", Integer.class)).isEqualTo(-230072270);
        jdbc.update("INSERT INTO files(id,user_id,purpose,bucket,object_key,name,mime,size,sha256) VALUES ('legacy-file','legacy-owner','RESUME','test','legacy.txt','legacy.txt','text/plain',10,'private-fixture')");
        jdbc.update("INSERT INTO resumes(id,user_id,title,source_file_id,parse_status) VALUES ('legacy-resume','legacy-owner','Keep my resume','legacy-file','READY')");
        jdbc.update("INSERT INTO resume_versions(id,resume_id,user_id,version_no,source,structured,plain_text) VALUES ('legacy-v','legacy-resume','legacy-owner',1,'OPTIMIZED','{\"summary\":\"Before\"}','Before')");
        jdbc.update("UPDATE resumes SET current_version_id='legacy-v' WHERE id='legacy-resume'");
        jdbc.update("INSERT INTO resume_patch_batches(id,user_id,resume_id,resume_version_id,patches) VALUES ('legacy-batch','legacy-owner','legacy-resume','legacy-v','[{\"path\":\"/summary\",\"before\":\"Before\",\"after\":\"After\"}]')");
        if (!target.equals("11")) {
            assertThat(jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='12'", Integer.class)).isEqualTo(-115544576);
            jdbc.update("INSERT INTO resume_optimizations(id,user_id,resume_id,version_id,request_id,status,model_tier,job_description) VALUES ('legacy-report','legacy-owner','legacy-resume','legacy-v','legacy-request','FAILED','CHEAP','Keep my JD')");
        }
        if (target.equals("13")) {
            assertThat(jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='13'", Integer.class)).isEqualTo(427451572);
            jdbc.update("INSERT INTO jobs(id,site,external_id,nature,payload) VALUES ('legacy-job','test','1','campus','{}')");
            jdbc.update("INSERT INTO applications(id,user_id,job_id,status,note) VALUES ('legacy-app','legacy-owner','legacy-job','INTERVIEW','Keep my note')");
        }
        align(url);
        align(url); // 可重复运行，不覆盖原历史备份。
        migrations(url, "latest", true).migrate();
        assertThat(migrations(url, "latest", false).validateWithResult().validationSuccessful).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(42);
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success", String.class))
                .contains("15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30", "31", "32", "33", "34", "40", "41", "42", "43", "44", "45", "46", "47");
        assertThat(jdbc.queryForObject("SELECT nickname FROM users WHERE id='legacy-owner'", String.class)).isEqualTo("Preserve me");
        assertThat(jdbc.queryForObject("SELECT source FROM resume_versions WHERE id='legacy-v'", String.class)).isEqualTo("OPTIMIZED");
        assertThat(jdbc.queryForObject("SELECT current_version_id FROM resumes WHERE id='legacy-resume'", String.class)).isEqualTo("legacy-v");
        assertThat(jdbc.queryForObject("SELECT resume_version_id FROM resume_patch_batches WHERE id='legacy-batch'", String.class)).isEqualTo("legacy-v");
        assertThat(jdbc.queryForObject("SELECT version FROM offerpilot_legacy_flyway_history_backup WHERE script='V11__resume_patch_batches.sql'", String.class)).isEqualTo("11");
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id='usr_test_user001'", String.class)).isEqualTo("DISABLED");
        if (!target.equals("11")) assertThat(jdbc.queryForObject("SELECT job_description FROM resume_optimizations WHERE id='legacy-report'", String.class)).isEqualTo("Keep my JD");
        if (target.equals("13")) {
            assertThat(jdbc.queryForObject("SELECT note FROM applications WHERE id='legacy-app'", String.class)).isEqualTo("Keep my note");
            assertThat(jdbc.queryForObject("SELECT status FROM applications WHERE id='legacy-app'", String.class)).isEqualTo("INTERVIEW");
        }
    }

    @Test
    void v41InterviewSnapshotsKeepAnswersOptionsAndLegacyReportSemantics() {
        String url=newDatabase(); migrations(url,"41",false).migrate(); var jdbc=jdbc(url);
        jdbc.update("INSERT INTO users(id,email,password_hash,nickname) VALUES ('iv-migration-owner','iv-migration@example.test','fixture','Migration')");
        jdbc.update("INSERT INTO interview_sessions(id,user_id,role,kind,question_index,completed,options) VALUES ('iv-before42','iv-migration-owner','护士','综合',4,true,'{\"resumeId\":null,\"versionId\":null,\"difficulty\":\"STANDARD\",\"mode\":\"TEXT\",\"durationMinutes\":30}')");
        for(int i=0;i<5;i++) jdbc.update("INSERT INTO interview_answers(session_id,question_index,question_text,answer_text,follow_up_question,follow_up_answer,follow_up_source) VALUES ('iv-before42',?,'旧问题','真实旧回答','旧追问','真实补充','rule')",i);
        migrations(url,"latest",false).migrate();
        assertThat(jdbc.queryForObject("SELECT generation_source IS NULL AND report IS NULL AND revision=0 AND completed AND options->>'difficulty'='STANDARD' FROM interview_sessions WHERE id='iv-before42'",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interview_answers WHERE session_id='iv-before42' AND answer_text='真实旧回答' AND follow_up_answer='真实补充' AND evaluation IS NULL",Integer.class)).isEqualTo(5);
    }

    @Test
    void modifiedHistoryIsRejectedAtomically() throws Exception {
        String url = legacy("12");
        jdbc(url).update("UPDATE flyway_schema_history SET checksum=1 WHERE version='12'");
        assertThatThrownBy(() -> align(url)).hasMessageContaining("Unknown, modified or failed");
        assertThat(jdbc(url).queryForObject("SELECT script FROM flyway_schema_history WHERE version='11'", String.class)).isEqualTo("V11__resume_patch_batches.sql");
        assertThat(jdbc(url).queryForObject("SELECT to_regclass('public.offerpilot_legacy_flyway_history_backup') IS NULL", Boolean.class)).isTrue();
        assertThat(jdbc(url).queryForObject("SELECT nickname FROM users WHERE id='legacy-owner'", String.class)).isEqualTo("Preserve me");
    }
}
