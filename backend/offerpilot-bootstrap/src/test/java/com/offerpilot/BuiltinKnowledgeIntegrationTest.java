package com.offerpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.error.*;
import com.offerpilot.knowledge.application.*;
import com.offerpilot.knowledge.web.BuiltinKnowledgeController;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.DockerImageName;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** PostgreSQL/Flyway/real seeds/JDBC/MockMvc. Run only where a cached Docker image is available. */
@Testcontainers
class BuiltinKnowledgeIntegrationTest {
    @Container static PostgreSQLContainer<?> db=new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));
    static final ObjectMapper json=new ObjectMapper();
    static JdbcTemplate jdbc;
    static BuiltinKnowledgeRepository repository;
    @BeforeAll static void migrateAndSeed() throws Exception {
        Flyway.configure().dataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()).load().migrate();
        jdbc=new JdbcTemplate(new DriverManagerDataSource(db.getJdbcUrl(),db.getUsername(),db.getPassword()));
        new OccupationSeeder(jdbc,json,new Resource[]{new ClassPathResource("seed/occupations.json"),new ClassPathResource("seed/occupations-onet.json")}).run(new DefaultApplicationArguments());
        new BuiltinKnowledgeSeeder(jdbc,json).run(new DefaultApplicationArguments());
        repository=new BuiltinKnowledgeRepository(jdbc,json);
    }
    @Test void actualSeedCountsPaginationFiltersDetailsAndIdempotency() throws Exception {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM occupations WHERE source='onet'",Integer.class)).isGreaterThanOrEqualTo(300);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM builtin_knowledge_entries WHERE kind='QUESTION' AND source='curated'",Integer.class)).isGreaterThanOrEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM occupations WHERE salary_range IS NOT NULL",Integer.class)).isZero();
        var first=repository.occupations("","","",1,20);var next=repository.occupations("","","",2,20);
        assertThat(first.total()).isEqualTo(402);assertThat(first.items()).hasSize(20).doesNotContainAnyElementsOf(next.items());
        var detail=repository.occupation("occ_backend_dev");assertThat(detail.onetCode()).isEqualTo("15-1252.00");assertThat(detail.salaryRange()).isNull();assertThat(detail.salaryNotice()).contains("暂无数据");
        var filtered=repository.entries(new BuiltinKnowledgeRepository.Filters("QUESTION","","医疗健康","护士","CASE","HARD",1,20));
        assertThat(filtered.total()).isEqualTo(2);assertThat(filtered.items()).allSatisfy(q -> {assertThat(q.source()).isEqualTo("curated");assertThat(q.content()).contains("护士");});
        var before=jdbc.queryForList("SELECT id,revision,updated_at FROM builtin_knowledge_entries ORDER BY id");
        new BuiltinKnowledgeSeeder(jdbc,json).run(new DefaultApplicationArguments());
        assertThat(jdbc.queryForList("SELECT id,revision,updated_at FROM builtin_knowledge_entries ORDER BY id")).isEqualTo(before);
        assertThatThrownBy(() -> repository.entry("missing")).isInstanceOf(BusinessException.class);
    }
    @Test void localVectorSearchAndFulltextDegradationNeverUseExternalModelsAndExcludePlannedReports() {
        var embedding=mock(OccupationEmbeddings.class);when(embedding.fingerprint()).thenReturn("bge-m3@local");when(embedding.configured()).thenReturn(true);
        var vector=new float[1024];vector[0]=1;when(embedding.embed(anyList())).thenAnswer(call -> ((List<?>)call.getArgument(0)).stream().map(x -> vector).toList());
        var search=new BuiltinKnowledgeSearch(jdbc,embedding);
        var lexical=search.search("护理","QUESTION",20);assertThat(lexical.items()).isNotEmpty();
        assertThat(lexical.vectorAvailable()).isFalse();
        var index=new BuiltinKnowledgeIndex(jdbc,embedding);assertThat(index.reindex()).isEqualTo(20);
        assertThat(search.search("护理","",20).vectorAvailable()).isTrue();
        assertThat(search.search("护理","",20).items()).noneMatch(h -> h.kind().equals("REPORT"));
        var refs=new BuiltinQuestionReferences(jdbc).find("护士","医疗健康","HARD");
        assertThat(refs).hasSize(8).allSatisfy(r -> assertThat(r.source()).isEqualTo("curated"));
        assertThat(new BuiltinQuestionReferences(jdbc).find("不存在的岗位","","HARD")).isEmpty();
        when(embedding.fingerprint()).thenReturn("external@cloud");
        clearInvocations(embedding);assertThat(search.search("护理","QUESTION",20).vectorAvailable()).isFalse();verify(embedding,never()).embed(anyList());
        jdbc.update("UPDATE builtin_knowledge_entries SET embedding=NULL,embedding_fingerprint=NULL");
    }
    @Test void authenticatedHttpBoundaryUsesRealJdbcAndRealSeedFilters() throws Exception {
        CurrentUser users=auth -> { if(!"Bearer fixture".equals(auth)) throw new BusinessException(ErrorCode.UNAUTHENTICATED);return "verified"; };
        var embedding=mock(OccupationEmbeddings.class);
        var mvc=MockMvcBuilders.standaloneSetup(new BuiltinKnowledgeController(users,repository,new BuiltinKnowledgeSearch(jdbc,embedding))).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/v1/knowledge/builtin/facets")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/knowledge/builtin/entries").header("Authorization","Bearer fixture").param("kind","QUESTION").param("role","教师").param("questionType","CASE").param("difficulty","HARD"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(2)).andExpect(jsonPath("$.data.items[0].source").value("curated"));
        mvc.perform(get("/api/v1/knowledge/builtin/occupations/missing").header("Authorization","Bearer fixture")).andExpect(status().isNotFound());
    }
}
