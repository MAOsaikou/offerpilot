package com.offerpilot.knowledge.application;

import com.offerpilot.career.application.OccupationRecommender;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class OccupationIndexTest {
    @Test void occupationBatchRespectsBailianLimitAndPreservesVectorOrder() {
        @SuppressWarnings("unchecked") ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(provider.getIfAvailable()).thenReturn(model);
        when(model.embed(anyList())).thenAnswer(invocation -> {
            List<String> inputs = invocation.getArgument(0);
            if (inputs.size() > 10) throw new IllegalArgumentException("Bailian input limit exceeded");
            return inputs.stream().map(text -> {
                float[] vector = new float[1024];
                vector[0] = Integer.parseInt(text.substring("document-".length())) + 1;
                return vector;
            }).toList();
        });
        var env = new MockEnvironment().withProperty("spring.ai.openai.api-key", "test-only")
                .withProperty("spring.ai.model.embedding", "openai");
        var documents = java.util.stream.IntStream.range(0, 16).mapToObj(i -> "document-" + i).toList();
        var vectors = new OccupationEmbeddings(provider, env).embed(documents);
        assertThat(vectors).hasSize(16);
        for (int i = 0; i < vectors.size(); i++) assertThat(vectors.get(i)[0]).isEqualTo(i + 1);
        verify(model).embed(documents.subList(0, 10));
        verify(model).embed(documents.subList(10, 16));
    }

    @Test void laterProviderBatchFailureDiscardsPartialResults() {
        @SuppressWarnings("unchecked") ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(provider.getIfAvailable()).thenReturn(model);
        var vector = new float[1024];
        vector[0] = 1;
        when(model.embed(anyList())).thenReturn(java.util.Collections.nCopies(10, vector))
                .thenThrow(new IllegalStateException("supplier unavailable"));
        var env = new MockEnvironment().withProperty("spring.ai.openai.api-key", "test-only")
                .withProperty("spring.ai.model.embedding", "openai");
        var documents = java.util.Collections.nCopies(16, "Java");
        assertThat(new OccupationEmbeddings(provider, env).embed(documents)).isEmpty();
        verify(model, times(2)).embed(anyList());
    }

    @Test void rrfUsesOneBasedRanksAndOneVotePerList() {
        var scores = OccupationRrf.scores(List.of("a", "a", "b"), List.of("b", "c"));
        assertThat(scores.get("a")).isEqualTo(1.0 / 61);
        assertThat(scores.get("b")).isEqualTo(1.0 / 62 + 1.0 / 61);
        assertThat(scores.get("c")).isEqualTo(1.0 / 62);
    }


    @Test void missingKeyDisabledModelFailuresAndBadDimensionsNeverEscape() {
        @SuppressWarnings("unchecked") ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(provider.getIfAvailable()).thenReturn(model);
        var env = new MockEnvironment();
        var embeddings = new OccupationEmbeddings(provider, env);
        assertThat(embeddings.embed(List.of("Java"))).isEmpty();
        verifyNoInteractions(provider);
        env.withProperty("spring.ai.openai.api-key", "test-only").withProperty("spring.ai.model.embedding", "none");
        assertThat(embeddings.embed(List.of("Java"))).isEmpty();
        verifyNoInteractions(provider);
        env.withProperty("spring.ai.model.embedding", "openai");
        when(model.embed(anyList())).thenThrow(new IllegalStateException("supplier unavailable"));
        assertThat(embeddings.embed(List.of("Java"))).isEmpty();
        doReturn(List.of(new float[3])).when(model).embed(anyList());
        assertThat(embeddings.embed(List.of("Java"))).isEmpty();
        assertThatThrownBy(() -> OccupationEmbeddings.literal(new float[1024])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void lexicalOnlyAndVectorRetrievalBothFuseWithoutChangingSimilarity() {
        var profile = new com.offerpilot.career.application.RiasecProfile(1, 2, 0, 0, 0, 1);
        var retrieval = mock(OccupationHybridRetriever.class);
        var rules = List.of(new OccupationRecommender.Ranked("a", "工程师", "技术", "IRC", 1),
                new OccupationRecommender.Ranked("b", "分析师", "数据", "ICR", .75));
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<OccupationRecommender.Ranked>>any()))
                .thenReturn(rules);
        var recommender = new OccupationRecommender(jdbc, retrieval);
        for (boolean vectors : List.of(false, true)) {
            when(retrieval.search("Java", 4)).thenReturn(new OccupationHybridRetriever.Retrieval(List.of(
                    new OccupationHybridRetriever.Hit("b", "分析师", "数据", "ICR", .01, "SQL", true, vectors)), vectors));
            var fused = recommender.rankHybrid(profile, "Java", 2);
            assertThat(fused).extracting(OccupationRecommender.Ranked::id).containsExactly("b", "a");
            assertThat(fused.get(0).similarity()).isEqualTo(.75);
            assertThat(fused.get(0).citations()).singleElement().satisfies(c -> assertThat(c.excerpt()).isEqualTo("SQL"));
            assertThat(fused.get(1).citations()).isEmpty();
        }
        when(retrieval.search("Java", 4)).thenReturn(new OccupationHybridRetriever.Retrieval(List.of(
                new OccupationHybridRetriever.Hit("b", "分析师", "数据", "ICR", .01, "SQL", false, true)), true));
        assertThat(recommender.rankHybrid(profile, "Java", 2).get(0).retrievalReason())
                .contains("向量检索").doesNotContain("文本中匹配");
        assertThat(recommender.rankHybrid(profile, " ", 2)).isEqualTo(rules);
        when(retrieval.search("Java", 4)).thenReturn(new OccupationHybridRetriever.Retrieval(List.of(), false));
        assertThat(recommender.rankHybrid(profile, "Java", 2)).isEqualTo(rules);
        when(retrieval.search("Java", 4)).thenThrow(new IllegalStateException("database unavailable"));
        assertThat(recommender.rankHybrid(profile, "Java", 2)).isEqualTo(rules);
    }

    @Test void hanBigramsAndLatinTokensAreGenericAndQuerySyntaxCannotEscape() {
        assertThat(OccupationDocument.lexicalTokens("后端开发 Java，研究技术"))
                .containsExactly("后端", "端开", "开发", "java", "研究", "究技", "技术");
        assertThat(OccupationDocument.lexicalTokens("Java工程师SQL"))
                .containsExactly("java", "工程", "程师", "sql");
        assertThat(OccupationDocument.lexicalQuery("Java') | ! SQL:*")).isEqualTo("'java' | 'sql'");
        assertThat(OccupationDocument.lexicalQuery("😀！？")).isEmpty();
    }
}
