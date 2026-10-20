package com.offerpilot;

import com.fasterxml.jackson.databind.*;
import com.offerpilot.knowledge.application.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real packaged seed assets; does not connect to any database or embedding provider. */
class BuiltinKnowledgeSeedTest {
    final ObjectMapper json=new ObjectMapper();
    JsonNode seed(String name) throws Exception {
        try(var in=new ClassPathResource("seed/"+name+".json").getInputStream()) { return json.readTree(in); }
    }
    @Test void realOccupationsHaveAtLeast300DirectOfficialSourcesAndNoEstimatedSalaries() throws Exception {
        var direct=seed("occupations-onet").path("occupations");
        assertThat(direct.size()).isGreaterThanOrEqualTo(300).isEqualTo(378);
        var ids=new HashSet<String>();var codes=new HashSet<String>();
        for(String name:List.of("occupations","occupations-onet")) for(var e:seed(name).path("occupations")) {
            assertThat(ids.add(e.path("id").asText())).isTrue();
            assertThat(e.path("salaryRange").isNull()).isTrue();
            assertThat(e.path("onetCode").asText()).matches("\\d{2}-\\d{4}\\.\\d{2}");
            assertThat(e.path("sourceUrl").asText()).endsWith(e.path("onetCode").asText());
            assertThat(e.path("license").asText()).isEqualTo("CC BY 4.0");
            assertThat(e.path("attribution").asText()).contains("National Center for O*NET Development");
            assertThat(e.path("contentNotice").asText()).isNotBlank();
            assertThat(e.path("hollandScores").size()).isEqualTo(6);
            e.path("hollandScores").forEach(v -> assertThat(v.asDouble()).isBetween(0.0,1.0));
            if("onet".equals(e.path("source").asText())) assertThat(codes.add(e.path("onetCode").asText())).isTrue();
        }
        assertThat(ids).hasSize(402);assertThat(codes).hasSize(378);
    }
    @Test void realQuestionSeedExceeds500AndCoversAllTypesDifficultiesAndNonTechnicalRoles() throws Exception {
        var entries=seed("builtin-knowledge").path("entries");
        var ids=new HashSet<String>();var texts=new HashSet<String>();var types=new HashSet<String>();var levels=new HashSet<String>();var roles=new HashSet<String>();var counts=new HashMap<String,Integer>();
        for(var e:entries) {
            assertThat(ids.add(e.path("id").asText())).isTrue();
            counts.merge(e.path("kind").asText(),1,Integer::sum);
            if("REPORT".equals(e.path("kind").asText())) {
                assertThat(e.path("status").asText()).isEqualTo("PLANNED");
                assertThat(e.path("payload").path("metrics")).isEmpty();
                assertThat(e.path("payload").path("reportUrl").isNull()).isTrue();
            }
            if(!"QUESTION".equals(e.path("kind").asText())) continue;
            assertThat(e.path("source").asText()).isEqualTo("curated");
            assertThat(e.path("notice").asText()).contains("非真实候选人面经","未逐题专家校审");
            assertThat(e.path("industry").asText()).isNotBlank();assertThat(e.path("role").asText()).isNotBlank();
            assertThat(e.path("payload").path("answerGuide").size()).isGreaterThan(0);
            assertThat(texts.add(e.path("content").asText())).isTrue();
            types.add(e.path("questionType").asText());levels.add(e.path("difficulty").asText());roles.add(e.path("role").asText());
        }
        assertThat(texts.size()).isGreaterThanOrEqualTo(500).isEqualTo(576);
        assertThat(types).containsExactlyInAnyOrder("BEHAVIORAL","SITUATIONAL","KNOWLEDGE","CASE");
        assertThat(levels).containsExactlyInAnyOrder("EASY","STANDARD","HARD");
        assertThat(roles).hasSize(24).contains("护士","教师","会计专员","销售代表","人力资源专员","社会工作者","法务助理");
        assertThat(counts).containsEntry("COMPETENCY",24).containsEntry("CAMPUS",6).containsEntry("REPORT",10);
    }
    @Test void startupUsesRealMaterializedSeedsWithMatchingSqlBindingsAndFulltext() throws Exception {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.update(anyString(),any(Object[].class))).thenAnswer(call -> {
            String sql=call.getArgument(0);
            Object[] args=java.util.Arrays.copyOfRange(call.getArguments(),1,call.getArguments().length);
            assertThat(sql.chars().filter(c -> c=='?').count()).isEqualTo(args.length);
            return 1;
        });
        new OccupationSeeder(jdbc,json,new Resource[]{new ClassPathResource("seed/occupations.json"),new ClassPathResource("seed/occupations-onet.json")}).run(new DefaultApplicationArguments());
        new BuiltinKnowledgeSeeder(jdbc,json).run(new DefaultApplicationArguments());
        verify(jdbc,times(1018)).update(anyString(),any(Object[].class));
    }
}
