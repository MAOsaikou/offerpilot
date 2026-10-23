package com.offerpilot.knowledge.application;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RetrievalDebugTest {
    @Test void exposesIndependentRecallAndCoverageScoresWithStableRanksAndMissingSignals() {
        var lexical=List.of(new RetrievalDebug.Candidate("a","Java","基础",0.8,null),new RetrievalDebug.Candidate("b","Java Redis","缓存",0.3,null));
        var vector=List.of(new RetrievalDebug.Candidate("a","Java","基础",null,0.95),new RetrievalDebug.Candidate("c","Java Redis","缓存",null,0.7));
        var result=RetrievalDebug.rank("Java Redis",10,lexical,vector,true);
        assertThat(result.items()).extracting(RetrievalDebug.Hit::id).containsExactly("c","b","a");
        var b=result.items().get(1);
        assertThat(b.recallScore()).isCloseTo(0.5/62,within(0.000001));
        assertThat(b.rerankScore()).isEqualTo(1);
        assertThat(b.vectorScore()).isNull();assertThat(b.vectorRank()).isNull();
        var a=result.items().get(2);
        assertThat(a.recallRank()).isEqualTo(1);assertThat(a.rerankScore()).isEqualTo(0.5);
        assertThat(a.lexicalScore()).isEqualTo(0.8);assertThat(a.vectorScore()).isEqualTo(0.95);
        assertThat(RetrievalDebug.rank("空",10,List.of(),List.of(),false).items()).isEmpty();
    }
    @Test void remoteEmbeddingIsNeverCalledByDebugEndpoint() {
        var jdbc=mock(JdbcTemplate.class);var embeddings=mock(OccupationEmbeddings.class);
        when(embeddings.fingerprint()).thenReturn("text-embedding-v4@external.example");
        var debug=new RetrievalDebug(jdbc,embeddings);
        assertThat(debug.search("Java",10).vectorAvailable()).isFalse();
        assertThat(debug.search("Java",10,"staff_docs").vectorAvailable()).isFalse();
        assertThatThrownBy(()->debug.search("Java",10,"knowledge_notes")).isInstanceOf(com.offerpilot.common.error.BusinessException.class);
        verify(embeddings,never()).embed(anyList());
        assertThatThrownBy(()->debug.search(" ",10)).isInstanceOf(com.offerpilot.common.error.BusinessException.class);
        assertThatThrownBy(()->debug.search("Java",51)).isInstanceOf(com.offerpilot.common.error.BusinessException.class);
    }
}
