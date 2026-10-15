package com.offerpilot;

import com.offerpilot.career.application.OccupationRecommender;
import com.offerpilot.knowledge.application.OccupationEmbeddings;
import com.offerpilot.knowledge.application.OccupationHybridRetriever;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 显式启用的真实 embedding 对照（百炼或本地 Ollama bge-m3，按 EMBEDDING_* 环境变量）；结果写入 target，不进 CI。 */
@EnabledIfEnvironmentVariable(named = "OFFERPILOT_RUN_LIVE_RAG", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.ai.model.chat=deepseek", "spring.ai.model.embedding=openai", "spring.ai.retry.max-attempts=2",
        "spring.ai.mcp.server.enabled=false", "offerpilot.knowledge.occupation-embed.enabled=true"})
@ActiveProfiles("test")
@Testcontainers
@Transactional
class OccupationRagLiveEvaluationTest {
    @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", db::getJdbcUrl);
        registry.add("spring.datasource.username", db::getUsername);
        registry.add("spring.datasource.password", db::getPassword);
    }
    @Autowired OccupationRecommender recommender;
    @Autowired OccupationHybridRetriever retriever;
    @Autowired OccupationEmbeddings embeddings;
    @Autowired JdbcTemplate jdbc;

    record Case(String code, String query, String expected) {}

    /** 前 9 条与 OccupationRagIntegrationTest 相同的关键词意图；后 10 条是学生会说的自然语句。 */
    static final List<Case> CASES = List.of(
            new Case("IRC", "Java Spring Boot 后端", "occ_backend_dev"),
            new Case("IRC", "前端 TypeScript Vue", "occ_frontend_dev"),
            new Case("ICR", "SQL 数据分析", "occ_data_analyst"),
            new Case("IRC", "机器学习 Python", "occ_ml_engineer"),
            new Case("ASE", "产品 需求 用户", "occ_product_manager"),
            new Case("AIR", "设计 Figma", "occ_ui_designer"),
            new Case("IRC", "测试 自动化", "occ_test_dev"),
            new Case("IRC", "运维 Docker Kubernetes", "occ_devops_cloud"),
            new Case("IRC", "安全 渗透测试", "occ_security_engineer"),
            new Case("IAR", "我喜欢把网页做得好看又流畅，常调交互动画和组件", "occ_frontend_dev"),
            new Case("IRC", "想做高并发的服务端接口和数据库设计", "occ_backend_dev"),
            new Case("ICE", "平时爱用 Excel 和 SQL 做报表，从数据里找原因", "occ_data_analyst"),
            new Case("IRC", "对大模型应用很感兴趣，做过 RAG 和智能体小项目", "occ_llm_app_engineer"),
            new Case("IRC", "喜欢研究图像识别和目标检测", "occ_cv_engineer"),
            new Case("ESA", "喜欢跟用户聊需求，把想法写成方案推动大家落地", "occ_product_manager"),
            new Case("IRC", "参加过 CTF，喜欢找系统漏洞", "occ_security_engineer"),
            new Case("IRC", "想写安卓和 iOS 应用", "occ_mobile_dev"),
            new Case("RIC", "希望保障线上服务稳定，处理故障和监控告警", "occ_sre"),
            new Case("AIR", "喜欢玩游戏，也想用 Unity 做游戏", "occ_game_client_dev"));

    @Test void bailianHybridVersusLexicalAndRules() throws Exception {
        int total = jdbc.queryForObject("SELECT count(*) FROM occupations", Integer.class);
        int embedded = jdbc.queryForObject("SELECT count(*) FROM occupations WHERE embedding IS NOT NULL", Integer.class);
        assertThat(embedded).as("startup job embedded the whole catalog with Bailian").isEqualTo(total);
        var report = new StringBuilder("模型：" + embeddings.fingerprint() + "\n\n| # | Holland | 查询 | 目标 | rules | lexical | vector-only | hybrid(规则⊕全文⊕向量) |\n|---|---|---|---|---|---|---|---|\n");
        int[] hits = new int[4]; int[] top1 = new int[4];
        String[][] rows = new String[CASES.size()][4];
        for (int i = 0; i < CASES.size(); i++) {
            Case c = CASES.get(i);
            rows[i][0] = rank(recommender.rank(c.code(), 3));
            rows[i][3] = rank(recommender.rankHybrid(c.code(), c.query(), 3));
            var vector = embeddings.embed(List.of(c.query()));
            rows[i][2] = String.join(", ", jdbc.queryForList("""
                    SELECT id FROM occupations WHERE embedding IS NOT NULL
                    ORDER BY embedding <=> CAST(? AS vector),id LIMIT 3
                    """, String.class, OccupationEmbeddings.literal(vector.get(0))));
        }
        // Lexical-only: same fusion with vectors hidden (rolled back after the test).
        jdbc.update("UPDATE occupations SET embedding=NULL");
        for (int i = 0; i < CASES.size(); i++) rows[i][1] = rank(recommender.rankHybrid(CASES.get(i).code(), CASES.get(i).query(), 3));
        for (int i = 0; i < CASES.size(); i++) {
            Case c = CASES.get(i);
            report.append("| ").append(i + 1).append(" | ").append(c.code()).append(" | ").append(c.query())
                    .append(" | ").append(c.expected());
            for (int k = 0; k < 4; k++) {
                List<String> ids = List.of(rows[i][k].split(", "));
                if (ids.contains(c.expected())) hits[k]++;
                if (ids.get(0).equals(c.expected())) top1[k]++;
                report.append(" | ").append(rows[i][k].replace(c.expected(), "**" + c.expected() + "**"));
            }
            report.append(" |\n");
        }
        report.append("\n| 指标 | rules | lexical | vector-only | hybrid |\n|---|---|---|---|---|\n");
        report.append("| Hit@3 (1-9 关键词) | ").append(count(rows, 0, 9, 0)).append(" | ").append(count(rows, 0, 9, 1))
                .append(" | ").append(count(rows, 0, 9, 2)).append(" | ").append(count(rows, 0, 9, 3)).append(" |\n");
        report.append("| Hit@3 (10-19 自然语句) | ").append(count(rows, 9, 19, 0)).append(" | ").append(count(rows, 9, 19, 1))
                .append(" | ").append(count(rows, 9, 19, 2)).append(" | ").append(count(rows, 9, 19, 3)).append(" |\n");
        report.append("| Hit@3 合计 | ").append(hits[0]).append(" | ").append(hits[1]).append(" | ").append(hits[2]).append(" | ").append(hits[3]).append(" |\n");
        report.append("| Top1 合计 | ").append(top1[0]).append(" | ").append(top1[1]).append(" | ").append(top1[2]).append(" | ").append(top1[3]).append(" |\n");
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/occupation-rag-live-eval.md"), report.toString());
    }

    private static String count(String[][] rows, int from, int to, int k) {
        int n = 0;
        for (int i = from; i < to; i++) if (List.of(rows[i][k].split(", ")).contains(CASES.get(i).expected())) n++;
        return n + "/" + (to - from);
    }

    private static String rank(List<OccupationRecommender.Ranked> ranked) {
        return String.join(", ", ranked.stream().map(OccupationRecommender.Ranked::id).toList());
    }
}
