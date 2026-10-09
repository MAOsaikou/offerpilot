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
        assertThat(jdbc.queryForObject("SELECT count(*) FROM occupations WHERE holland_scores IS NULL "
                + "OR NOT (holland_scores ?& ARRAY['R','I','A','S','E','C'])", Integer.class))
                .as("全部种子均保留六维兴趣评分").isZero();
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
    @Test void demoProfileAndLexicalRetrievalRankActualLibraryWithoutVectors() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var demo = json.readTree(getClass().getResourceAsStream("/demo/journey.json")).path("career");
        var interests = json.convertValue(demo.path("interests"), new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() {});
        var derived = com.offerpilot.career.application.InterestHollandMapping.derive(interests);
        assertThat(derived.code()).isEqualTo("RIS");
        assertThat(derived.profile()).isEqualTo(new com.offerpilot.career.application.RiasecProfile(2, 2, 0, 1, 1, 0));
        String query = String.join(" ", derived.interestsUsed()) + " " + demo.path("skills").asText() + " "
                + demo.path("target").asText() + " 我喜欢研究技术和动手搭建系统";
        assertThat(jdbc.queryForObject("SELECT count(*) FROM occupations WHERE embedding IS NOT NULL", Integer.class)).isZero();
        var top = recommender.rankHybrid(derived.profile(), query, 8);
        System.out.println("DEMO_PROFILE_LEXICAL_TOP5 " + top.stream().limit(5).map(OccupationRecommender.Ranked::name).toList());
        assertThat(top.stream().limit(5).map(OccupationRecommender.Ranked::id)).containsAnyOf("occ_backend_dev", "occ_fullstack_dev", "occ_devops_cloud", "occ_test_dev", "occ_security_engineer");
        assertThat(top.stream().limit(3).map(OccupationRecommender.Ranked::name)).doesNotContain("假肢与矫形器师", "兽医", "口腔修复科医生");
        assertThat(top).anySatisfy(item -> {
            assertThat(item.retrievalReason()).contains("职业库文本中匹配");
            assertThat(item.citations()).hasSize(1);
        });
        assertThat(recommender.rankHybrid(derived.profile(), query, 8)).isEqualTo(top);
        System.out.println("DEMO_LETTER_BASELINE_TOP5 " + recommender.rank("RIS", 5).stream().map(OccupationRecommender.Ranked::name).toList());
    }

    @Test void chineseFreeTextWorksWithoutSegmenterOrVectors() {
        String query = "我想做后端开发";
        assertThat(jdbc.queryForObject("SELECT count(*) FROM occupations WHERE tsv @@ plainto_tsquery('simple', ?)",
                Integer.class, query)).isZero();
        assertThat(recommender.retrieve(query, 20)).extracting(com.offerpilot.knowledge.application.OccupationHybridRetriever.Hit::id)
                .contains("occ_backend_dev");
    }

    @Test @org.springframework.transaction.annotation.Transactional
    void closeFullProfileBeatsLiteralLetterCodeMatch() {
        jdbc.update("DELETE FROM occupations");
        jdbc.update("""
                INSERT INTO occupations(id,name,family,description,holland_code,holland_scores,source) VALUES
                ('close','接近画像','fixture','fixture','IRS','{"R":4,"I":5,"A":0,"S":2,"E":2,"C":0}','test'),
                ('letters','仅字母相同','fixture','fixture','RIS','{"R":7,"I":6,"A":5,"S":5.1,"E":0,"C":0}','test')
                """);
        var profile = new com.offerpilot.career.application.RiasecProfile(2, 2, 0, 1, 1, 0);
        assertThat(recommender.rank(profile, 2)).extracting(OccupationRecommender.Ranked::id).containsExactly("close", "letters");
        assertThat(recommender.rank("RIS", 2).get(0).id()).isEqualTo("letters");
    }
}
