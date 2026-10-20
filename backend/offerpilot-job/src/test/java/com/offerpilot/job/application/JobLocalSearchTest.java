package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.*;
import com.offerpilot.job.application.JobSearchParser.Criteria;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobLocalSearchTest {
    final ObjectMapper json=new ObjectMapper();
    final JobEmbeddings embeddings=mock(JobEmbeddings.class);
    final JobLocalSearch search=spy(new JobLocalSearch(mock(JdbcTemplate.class),json,embeddings));
    JsonNode job(String id) { return json.createObjectNode().put("id",id); }
    @Test void reciprocalRankFusionPromotesTheJobRecalledByBothChannels() {
        when(embeddings.enabled()).thenReturn(true); when(embeddings.key()).thenReturn("bge-stub"); when(embeddings.embed(anyString())).thenReturn("vector-stub");
        doReturn(List.of(job("lexical"),job("both")),List.of(job("semantic"),job("both"))).when(search).read(anyString(),anyList());
        var result=search.search("owner",new Criteria("Java开发","","","all","","rule"));
        assertThat(result.vectorAvailable()).isTrue();
        assertThat(result.items()).extracting(j->j.path("id").asText()).containsExactly("both","lexical","semantic");
        assertThat(JobKeywords.tokens("Java开发")).containsExactly("java","开发");
    }
    @Test void unavailableEmbeddingOrVectorIndexKeepsKeywordRecall() {
        when(embeddings.enabled()).thenReturn(true);
        doReturn(List.of(job("lexical"))).when(search).read(anyString(),anyList());
        var criteria=new Criteria("Java","","","all","","rule");
        assertThat(search.search("owner",criteria).items()).extracting(j->j.path("id").asText()).containsExactly("lexical");
        when(embeddings.embed(anyString())).thenThrow(new IllegalStateException("provider unavailable"));
        var result=search.search("owner",criteria);
        assertThat(result.vectorAvailable()).isFalse(); assertThat(result.items()).hasSize(1);
    }
}
