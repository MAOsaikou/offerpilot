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
    @Test void rrfUsesOneBasedRanksAndOneVotePerList() {
        var scores = OccupationRrf.scores(List.of("a", "a", "b"), List.of("b", "c"));
        assertThat(scores.get("a")).isEqualTo(1.0 / 61);
        assertThat(scores.get("b")).isEqualTo(1.0 / 62 + 1.0 / 61);
        assertThat(scores.get("c")).isEqualTo(1.0 / 62);
    }

    @Test void canonicalDocumentFlattensSkillsAndBoundsOriginalExcerpt() {
        assertThat(OccupationDocument.build("后端", "构建服务", "[{\"name\":\"Java\",\"level\":\"必备\"}]",
                "高级工程师", "技术", new String[]{"Java工程师"}))
                .isEqualTo("name: 后端\ndescription: 构建服务\nskills: Java / 必备\ngrowth: 高级工程师\nfamily: 技术\ntitles: Java工程师");
        assertThat(OccupationDocument.excerpt("", "[\"SQL\"]")).isEqualTo("SQL");
        String excerpt = OccupationDocument.excerpt("😀".repeat(125), "[]");
        assertThat(excerpt.codePointCount(0, excerpt.length())).isEqualTo(120);
        assertThat(OccupationDocument.excerpt("第一行\n 第二行", "[]")).isEqualTo("第一行 第二行");
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

    @Test void absentVectorsBlankQueriesAndRetrievalFailurePreserveRuleOrderAndScore() {
        var retrieval = mock(OccupationHybridRetriever.class);
        var rules = List.of(new OccupationRecommender.Ranked("a", "工程师", "技术", "IRC", 6),
                new OccupationRecommender.Ranked("b", "分析师", "数据", "ICR", 4.5));
        var recommender = spy(new OccupationRecommender(mock(JdbcTemplate.class), retrieval));
        doReturn(rules).when(recommender).rank("IRC", 4);
        when(retrieval.search("Java", 4)).thenReturn(new OccupationHybridRetriever.Retrieval(List.of(
                new OccupationHybridRetriever.Hit("b", "分析师", "数据", "ICR", .01, "SQL")), false));
        assertThat(recommender.rankHybrid("IRC", "Java", 2)).isEqualTo(rules);
        assertThat(recommender.rankHybrid("IRC", " ", 2)).isEqualTo(rules);
        when(retrieval.search("Java", 4)).thenThrow(new IllegalStateException("database unavailable"));
        assertThat(recommender.rankHybrid("IRC", "Java", 2)).isEqualTo(rules);
    }
}
