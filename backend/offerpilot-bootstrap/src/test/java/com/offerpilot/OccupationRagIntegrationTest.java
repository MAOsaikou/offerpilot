package com.offerpilot;

import com.offerpilot.career.application.CareerRecommendForChat;
import com.offerpilot.career.application.CareerProfileService;
import com.offerpilot.career.application.DefaultCareerRecommendForChat;
import com.offerpilot.career.application.OccupationRecommender;
import com.offerpilot.knowledge.application.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.ai.model.chat=deepseek", "spring.ai.deepseek.api-key=test-only",
        "spring.ai.model.embedding=none", "spring.ai.mcp.server.enabled=false",
        "offerpilot.knowledge.occupation-embed.enabled=true"})
@ActiveProfiles({"test", "mock-llm"})
@Import(OccupationMockEmbeddingConfiguration.class)
@Testcontainers
@Transactional
class OccupationRagIntegrationTest extends OccupationRecommendationTest {
    @Container static PostgreSQLContainer<?> db = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", db::getJdbcUrl);
        registry.add("spring.datasource.username", db::getUsername);
        registry.add("spring.datasource.password", db::getPassword);
    }
    @Autowired OccupationEmbeddingJob job;
    @Autowired OccupationEmbeddings embeddings;
    @Autowired OccupationHybridRetriever retrieval;
    @Autowired OccupationSeeder seeder;

    @Test void startupFillsV9AndSecondRunAndUnchangedSeedAreNoOps() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM occupations WHERE embedding IS NULL OR tsv IS NULL", Integer.class)).isZero();
        assertThat(job.reindex(false)).isEqualTo(new OccupationEmbeddingJob.Result(0, 0));
        seeder.run(new DefaultApplicationArguments());
        assertThat(job.reindex(false)).isEqualTo(new OccupationEmbeddingJob.Result(0, 0));
        jdbc.update("UPDATE occupations SET description='new Java service',updated_at=now() WHERE id='occ_backend_dev'");
        assertThat(job.reindex(false)).isEqualTo(new OccupationEmbeddingJob.Result(1, 1));
        jdbc.update("UPDATE occupations SET description='temporary',updated_at=now() WHERE id='occ_backend_dev'");
        seeder.run(new DefaultApplicationArguments());
        assertThat(jdbc.queryForObject("SELECT embedding IS NULL AND tsv IS NULL FROM occupations WHERE id='occ_backend_dev'", Boolean.class)).isTrue();
        assertThat(job.reindex(false)).isEqualTo(new OccupationEmbeddingJob.Result(1, 1));
    }

    @Test void switchingEmbeddingModelDropsIncomparableVectorsAndReindexes() {
        int total = jdbc.queryForObject("SELECT count(*) FROM occupations", Integer.class);
        assertThat(jdbc.queryForObject("SELECT fingerprint FROM embedding_index_state", String.class)).isEqualTo(embeddings.fingerprint());
        jdbc.update("UPDATE embedding_index_state SET fingerprint='legacy-embedding@other'");
        assertThat(job.reindex(false).embedded()).isEqualTo(total);
        assertThat(jdbc.queryForObject("SELECT fingerprint FROM embedding_index_state", String.class)).isEqualTo(embeddings.fingerprint());
        assertThat(job.reindex(false).embedded()).isZero();
    }

    @Test void fulltextWorksWithoutVectorsAndChatFusesWithRealCitations() {
        jdbc.update("UPDATE occupations SET embedding=NULL,tsv=NULL");
        var disabled = new OccupationEmbeddingJob(jdbc, embeddings, false, false);
        assertThat(disabled.reindex(false).embedded()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM occupations WHERE tsv IS NULL", Integer.class)).isZero();
        assertThat(recommender.retrieve("Java", 20)).extracting(OccupationHybridRetriever.Hit::id).contains("occ_backend_dev");
        assertThat(recommender.rankHybrid("IRC", "Java", 8)).isNotEqualTo(recommender.rank("IRC", 8));
        var profiles = mock(CareerProfileService.class);
        when(profiles.get("alice")).thenReturn(new CareerProfileService.Profile("后端开发工程师", "", "Java",
                List.of("技术研发", "数据分析"), List.of(), null));
        CareerRecommendForChat.Result result = new DefaultCareerRecommendForChat(profiles, recommender).recommend("alice");
        assertThat(result.payload().recommendations()).hasSize(8)
                .isSortedAccordingTo(java.util.Comparator.comparingDouble(CareerRecommendForChat.Recommendation::similarity).reversed()).allSatisfy(item -> {
            assertThat(item.citations()).allSatisfy(citation -> {
                assertThat(citation.occupationId()).isEqualTo(item.occupationId());
                assertThat(citation.title()).isEqualTo(item.name());
                assertThat(citation.excerpt()).isNotBlank().hasSizeLessThanOrEqualTo(120);
            });
        });
    }

    @Test void failedJobStillBackfillsFulltextAndRetriesSafely() {
        jdbc.update("UPDATE occupations SET embedding=NULL,tsv=NULL");
        var failing = mock(OccupationEmbeddings.class);
        when(failing.embed(anyList())).thenReturn(List.of());
        assertThat(new OccupationEmbeddingJob(jdbc, failing, true, false).reindex(false).embedded()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM occupations WHERE tsv IS NULL", Integer.class)).isZero();
        assertThat(job.reindex(false).embedded()).isGreaterThanOrEqualTo(300);
        assertThat(job.reindex(false).embedded()).isZero();
    }

    @Test void vectorAndFulltextFuseWithStableOrderAndHybridChat() {
        // Rollback restores rows, but catalog cases that delete/clear vectors leave dead HNSW
        // entries. Rebuild the tiny fixture index before asserting nearest-neighbor order.
        jdbc.execute("REINDEX INDEX idx_occupations_embedding");
        var query = jdbc.queryForObject("SELECT description FROM occupations WHERE id='occ_backend_dev'", String.class);
        var fused = retrieval.search(query, 5);
        assertThat(fused.vectorAvailable()).isTrue();
        assertThat(fused.hits().get(0).id()).isEqualTo("occ_backend_dev");
        // A repeated English lexeme fixture proves both SQL lists contribute, independent
        // of the hash model and the catalog's bilingual document token counts.
        jdbc.update("""
                INSERT INTO occupations(id,name,family,description,holland_code,source,embedding,tsv)
                VALUES ('occ_test_rrf','AAA Java fixture','test','Java Java Java','IRC','test',
                        CAST(? AS vector),to_tsvector('simple',?))
                """, OccupationEmbeddings.literal(OccupationMockEmbeddingConfiguration.hash("Java")), "Java ".repeat(100));
        var dual = retrieval.search("Java", 5);
        assertThat(dual.hits().get(0).id()).isEqualTo("occ_test_rrf");
        assertThat(dual.hits().get(0).rrf()).isEqualTo((1.0 + OccupationRrf.LEXICAL_WITH_VECTOR) / 61);
        assertThat(recommender.retrieve(query, 5)).isEqualTo(recommender.retrieve(query, 5));
        var profiles = mock(CareerProfileService.class);
        when(profiles.get("alice")).thenReturn(new CareerProfileService.Profile("后端开发工程师", "", "Java",
                List.of("技术研发", "数据分析"), List.of(), null));
        assertThat(new DefaultCareerRecommendForChat(profiles, recommender).recommend("alice").payload().recommendations())
                .anySatisfy(item -> assertThat(item.citations()).hasSize(1));
        assertThat(job.reindex(true).embedded()).isGreaterThanOrEqualTo(300);
    }

    @Test void lateProviderResponseCannotOverwriteNewerSourceVersion() {
        jdbc.update("UPDATE occupations SET embedding=NULL WHERE id='occ_backend_dev'");
        var delayed = mock(OccupationEmbeddings.class);
        when(delayed.embed(anyList())).thenAnswer(invocation -> {
            jdbc.update("UPDATE occupations SET description='changed during provider call',"
                    + "updated_at=updated_at + interval '1 second',tsv=NULL WHERE id='occ_backend_dev'");
            return List.of(OccupationMockEmbeddingConfiguration.hash("old document"));
        });
        assertThat(new OccupationEmbeddingJob(jdbc, delayed, true, false).reindex(false).embedded()).isZero();
        assertThat(jdbc.queryForObject("SELECT embedding IS NULL FROM occupations WHERE id='occ_backend_dev'", Boolean.class)).isTrue();
        assertThat(job.reindex(false)).isEqualTo(new OccupationEmbeddingJob.Result(1, 1));
    }

    @Test void tenCaseCatalogEvaluationWritesReproducibleReport() throws Exception {
        record Case(String code, String query, String expected) {}
        var cases = List.of(
                new Case("IRC", "Java Spring Boot 后端", "occ_backend_dev"),
                new Case("IRC", "前端 TypeScript Vue", "occ_frontend_dev"),
                new Case("ICR", "SQL 数据分析", "occ_data_analyst"),
                new Case("IRC", "机器学习 Python", "occ_ml_engineer"),
                new Case("ASE", "产品 需求 用户", "occ_product_manager"),
                new Case("AIR", "设计 Figma", "occ_ui_designer"),
                new Case("IRC", "测试 自动化", "occ_test_dev"),
                new Case("IRC", "运维 Docker Kubernetes", "occ_devops_cloud"),
                new Case("IRC", "安全 渗透测试", "occ_security_engineer"),
                new Case("IRC", "", "occ_backend_dev"));
        int rulesHits = 0, hybridHits = 0;
        var report = new StringBuilder("| Holland | 查询 | 目标 | rules top3 | hybrid top3 |\n|---|---|---|---|---|\n");
        for (Case item : cases) {
            assertThat(recommender.find(item.expected(), null).id()).isEqualTo(item.expected());
            var rules = recommender.rank(item.code(), 3).stream().map(OccupationRecommender.Ranked::id).toList();
            var hybrid = recommender.rankHybrid(item.code(), item.query(), 3).stream().map(OccupationRecommender.Ranked::id).toList();
            assertThat(hybrid).hasSize(3);
            assertThat(hybrid).isEqualTo(recommender.rankHybrid(item.code(), item.query(), 3)
                    .stream().map(OccupationRecommender.Ranked::id).toList());
            if (item.query().isBlank()) assertThat(hybrid).isEqualTo(rules);
            else {
                if (rules.contains(item.expected())) rulesHits++;
                if (hybrid.contains(item.expected())) hybridHits++;
            }
            report.append("| ").append(item.code()).append(" | ").append(item.query().isBlank() ? "（纯兴趣）" : item.query())
                    .append(" | ").append(item.expected()).append(" | ").append(String.join(", ", rules))
                    .append(" | ").append(String.join(", ", hybrid)).append(" |\n");
        }
        assertThat(hybridHits).as("lexical RAG improves these fixed career-intent fixtures").isGreaterThan(rulesHits);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/occupation-rag-eval.md"), report.toString());
    }
}
