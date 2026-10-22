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

    @Test void lexicalAndVectorCandidateSelectionKeepInterestScoresInDisplayedOrder() {
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
            assertThat(fused).isSortedAccordingTo(java.util.Comparator.comparingDouble(OccupationRecommender.Ranked::similarity).reversed());
            assertThat(fused).extracting(OccupationRecommender.Ranked::id).containsExactly("a", "b");
            assertThat(fused.get(1).similarity()).isEqualTo(.75);
            assertThat(fused.get(1).citations()).singleElement().satisfies(c -> assertThat(c.excerpt()).isEqualTo("SQL"));
            assertThat(fused.get(0).citations()).isEmpty();
        }
        when(retrieval.search("Java", 4)).thenReturn(new OccupationHybridRetriever.Retrieval(List.of(
                new OccupationHybridRetriever.Hit("b", "分析师", "数据", "ICR", .01, "SQL", false, true)), true));
        assertThat(recommender.rankHybrid(profile, "Java", 2).get(1).retrievalReason())
                .contains("语义检索").doesNotContain("文本中匹配");
        assertThat(recommender.rankHybrid(profile, " ", 2)).isEqualTo(rules);
        when(retrieval.search("Java", 4)).thenReturn(new OccupationHybridRetriever.Retrieval(List.of(), false));
        assertThat(recommender.rankHybrid(profile, "Java", 2)).isEqualTo(rules);
        when(retrieval.search("Java", 4)).thenThrow(new IllegalStateException("database unavailable"));
        assertThat(recommender.rankHybrid(profile, "Java", 2)).isEqualTo(rules);
    }

    @Test void semanticTopHitRemainsInCandidateSetAndDisplayedScoresAreDescending() {
        var catalog = new java.util.ArrayList<OccupationRecommender.Ranked>();
        for (int i = 0; i < 60; i++) catalog.add(new OccupationRecommender.Ranked("o" + i, "职业" + i, "技术", "IRC", 1 - i / 100.0));
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<OccupationRecommender.Ranked>>any()))
                .thenReturn(catalog);
        var retrieval = mock(OccupationHybridRetriever.class);
        // The user's words point to o45; the interest prior alone would rank it 46th of 60.
        when(retrieval.search("想写安卓和 iOS 应用", 6)).thenReturn(new OccupationHybridRetriever.Retrieval(List.of(
                new OccupationHybridRetriever.Hit("o45", "职业45", "技术", "IRC", .03, "移动端", false, true),
                new OccupationHybridRetriever.Hit("o2", "职业2", "技术", "IRC", .02, "客户端", false, true)), true));
        var fused = new OccupationRecommender(jdbc, retrieval)
                .rankHybrid(new com.offerpilot.career.application.RiasecProfile(1, 2, 0, 0, 0, 1), "想写安卓和 iOS 应用", 3);
        assertThat(fused).extracting(OccupationRecommender.Ranked::id).containsExactly("o0", "o2", "o45");
    }

    @Test void hanBigramsAndLatinTokensAreGenericAndQuerySyntaxCannotEscape() {
        assertThat(OccupationDocument.lexicalTokens("后端开发 Java，研究技术"))
                .containsExactly("后端", "端开", "开发", "java", "研究", "究技", "技术");
        assertThat(OccupationDocument.lexicalTokens("Java工程师SQL"))
                .containsExactly("java", "工程", "程师", "sql");
        assertThat(OccupationDocument.lexicalQuery("Java') | ! SQL:*")).isEqualTo("'java' | 'sql'");
        assertThat(OccupationDocument.lexicalQuery("😀！？")).isEmpty();
    }

    @Test void localOllamaCredentialIsIndependentOfChatKeysAndTestProfile() {
        @SuppressWarnings("unchecked") ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        var env = new MockEnvironment().withProperty("spring.ai.model.embedding","openai")
                .withProperty("spring.ai.openai.api-key","sk-not-configured")
                .withProperty("spring.ai.openai.embedding.api-key","ollama")
                .withProperty("spring.ai.openai.embedding.base-url","http://127.0.0.1:11434")
                .withProperty("spring.ai.openai.embedding.options.model","bge-m3");
        env.setActiveProfiles("test");
        var embeddings = new OccupationEmbeddings(provider,env);
        assertThat(embeddings.configured()).isTrue();
        assertThat(embeddings.fingerprint()).isEqualTo("bge-m3@local");
        env.withProperty("spring.ai.model.embedding","none");
        assertThat(embeddings.configured()).isFalse();
    }

    @Test void firstNoteEmbeddingFailureIsVisibleBeforeFinalFailureAndKeepsOwnerContext() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var embeddings = mock(OccupationEmbeddings.class);
        when(embeddings.configured()).thenReturn(true);
        when(embeddings.embed(anyList())).thenAnswer(call -> {
            assertThat(com.offerpilot.llm.quota.LlmUserContext.current()).isEqualTo("owner");
            return List.of();
        });
        var rs = mock(java.sql.ResultSet.class);
        when(rs.getString("id")).thenReturn("note"); when(rs.getString("user_id")).thenReturn("owner");
        when(rs.getLong("revision")).thenReturn(2L);
        when(rs.getString("title")).thenReturn("缓存击穿"); when(rs.getString("content")).thenReturn("互斥锁重建");
        when(rs.getString("category")).thenReturn("技术积累");
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.RowMapper<Object>>any()))
                .thenAnswer(call -> List.of(((org.springframework.jdbc.core.RowMapper<?>)call.getArgument(1)).mapRow(rs,0)));
        var worker = new KnowledgeNoteEmbeddingJob(jdbc,embeddings,true);
        when(rs.getInt("embedding_attempts")).thenReturn(1);
        assertThat(worker.indexPending()).isZero();
        verify(jdbc).update(contains("UPDATE knowledge_notes SET index_status=?"),eq("QUEUED"),contains("自动重试"),eq("note"),eq(2L),eq(1));
        when(rs.getInt("embedding_attempts")).thenReturn(3);
        assertThat(worker.indexPending()).isZero();
        verify(jdbc).update(contains("UPDATE knowledge_notes SET index_status=?"),eq("FAILED"),contains("保存笔记可重试"),eq("note"),eq(2L),eq(3));
        assertThat(com.offerpilot.llm.quota.LlmUserContext.current()).isNull();
    }
}
