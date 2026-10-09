package com.offerpilot;

import com.offerpilot.career.application.OccupationRecommender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证职业条目库能通过导入器落库，且规则层推荐能给出合理结果。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.ai.model.chat=deepseek", "spring.ai.model.embedding=none",
        "spring.ai.deepseek.api-key=occupation-test-only",
        "spring.ai.mcp.server.enabled=false", "offerpilot.resume.worker-enabled=false"})
@ActiveProfiles("test")
@Testcontainers
class OccupationRecommendationTest {
    @Container
    static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", db::getJdbcUrl);
        registry.add("spring.datasource.username", db::getUsername);
        registry.add("spring.datasource.password", db::getPassword);
    }

    @Autowired
    OccupationRecommender recommender;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void seederLoadsWholeOccupationLibrary() {
        Integer total = jdbc.queryForObject("SELECT count(*) FROM occupations", Integer.class);
        Integer fromOnet = jdbc.queryForObject("SELECT count(*) FROM occupations WHERE source='onet'", Integer.class);

        assertThat(total).as("需求为首批至少 300 条").isGreaterThanOrEqualTo(300);
        assertThat(fromOnet).as("O*NET 导入部分").isGreaterThanOrEqualTo(300);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM occupations WHERE holland_code IS NULL OR description = ''", Integer.class))
                .as("每条都应有兴趣码与描述").isZero();
    }

    @Test
    void ranksOccupationsByHollandCode() {
        var top = recommender.rank("AIS", 10);

        assertThat(top).hasSize(10);
        assertThat(top).isSortedAccordingTo(
                (a, b) -> Double.compare(b.similarity(), a.similarity()));
        assertThat(top.get(0).similarity()).isBetween(2.0, 6.0);
        assertThat(top).allSatisfy(ranked -> {
            assertThat(ranked.id()).isNotBlank();
            assertThat(ranked.name()).isNotBlank();
            assertThat(ranked.similarity()).isGreaterThan(0);
        });
    }

    @Test
    void exactCodeMatchScoresHighest() {
        // 取一条真实职业的兴趣码，用自己的码去查，应该拿到满分
        String code = jdbc.queryForObject(
                "SELECT holland_code FROM occupations WHERE id = 'occ_backend_dev'", String.class);
        var top = recommender.rank(code, 5);

        assertThat(top.get(0).similarity()).isEqualTo(6.0);
        assertThat(top).extracting(OccupationRecommender.Ranked::hollandCode).contains(code.trim());
    }

    @Test
    void invalidInputIsRejected() {
        assertThatThrownBy(() -> recommender.rank("XYZ", 5)).hasMessageContaining("RIASEC");
        assertThatThrownBy(() -> recommender.rank("", 5)).hasMessageContaining("RIASEC");
        assertThatThrownBy(() -> recommender.rank("AIS", 0)).hasMessageContaining("limit");
    }
    @Test void risSoftwareContextOutranksMedicalMatchesWithoutEmbeddingsAndStaysExplainable() {
        var top = recommender.rankContext("RIS", "技术研发 沟通协作 研究探索 后端开发 Java Spring Boot 我喜欢研究技术和动手搭建系统", 8);
        assertThat(top).hasSize(8).allSatisfy(item -> {
            assertThat(item.family()).isIn("软件研发", "算法与人工智能", "测试与质量", "运维与安全", "数据", "计算机与数学");
            assertThat(item.similarity()).isEqualTo(com.offerpilot.career.application.HollandMatcher.score("RIS", item.hollandCode()));
        });
        assertThat(recommender.rankContext("RIS", "", 8)).isEqualTo(recommender.rank("RIS", 8));
        assertThat(recommender.rankContext("RIS", "技术研发", 3)).extracting(OccupationRecommender.Ranked::family)
                .doesNotContain("医疗健康");
        assertThat(recommender.rankContext("RIS", "我喜欢研究技术和动手搭建系统", 8)).isEqualTo(
                recommender.rankContext("RIS", "我喜欢研究技术和动手搭建系统", 8));
    }
}
