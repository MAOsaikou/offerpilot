package com.offerpilot.knowledge.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BuiltinKnowledgeTest {
    @Test void validatesFiltersAndPaginationBeforeJdbcAccess() {
        var jdbc=mock(JdbcTemplate.class);var repo=new BuiltinKnowledgeRepository(jdbc,new ObjectMapper());
        assertThatThrownBy(() -> repo.occupations("","","XYZ",1,20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> repo.entries(new BuiltinKnowledgeRepository.Filters("QUESTION","","","","SQL","",1,20))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> repo.entries(new BuiltinKnowledgeRepository.Filters("DROP","","","","","",1,20))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> repo.occupations("","","",0,20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> repo.occupations("","","",100001,20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> repo.occupations("","","",1,101)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> repo.occupations("x".repeat(2001),"","",1,20)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(jdbc);
    }
    @Test void entryFiltersAreBoundAndHaveDeterministicPagination() {
        var jdbc=mock(JdbcTemplate.class);var repo=new BuiltinKnowledgeRepository(jdbc,new ObjectMapper());
        when(jdbc.queryForObject(anyString(),eq(Long.class),any(Object[].class))).thenReturn(3L);
        var result=repo.entries(new BuiltinKnowledgeRepository.Filters("QUESTION","x' OR 1=1 --","教育","教师","CASE","HARD",2,2));
        assertThat(result.total()).isEqualTo(3);assertThat(result.page()).isEqualTo(2);
        verify(jdbc).query(contains("ORDER BY id LIMIT ? OFFSET ?"),any(RowMapper.class),eq("QUESTION"),eq("教育"),eq("教师"),eq("CASE"),eq("HARD"),eq("x' OR 1=1 --"),anyString(),eq(2),eq(2));
        verify(jdbc).queryForObject(argThat(sql -> !sql.contains("x' OR")),eq(Long.class),any(Object[].class));
    }
    @Test void externalEmbeddingIsNeverCalledForIndexOrSearch() {
        var jdbc=mock(JdbcTemplate.class);var embeddings=mock(OccupationEmbeddings.class);
        when(embeddings.fingerprint()).thenReturn("text-embedding-v4@external.example");
        assertThat(new BuiltinKnowledgeIndex(jdbc,embeddings).reindex()).isZero();
        var result=new BuiltinKnowledgeSearch(jdbc,embeddings).search("护理","QUESTION",10);
        assertThat(result.vectorAvailable()).isFalse();assertThat(result.notice()).contains("仅全文");
        verify(embeddings,never()).embed(anyList());
        verify(jdbc,never()).queryForObject(anyString(),eq(Boolean.class),any(Object[].class));
    }
    @Test void failedLocalEmbeddingKeepsLexicalResultsAndDoesNotWritePartialVectors() {
        var jdbc=mock(JdbcTemplate.class);var embeddings=mock(OccupationEmbeddings.class);
        when(embeddings.fingerprint()).thenReturn("bge-m3@local");when(embeddings.configured()).thenReturn(true);
        when(jdbc.query(anyString(),any(RowMapper.class),eq("bge-m3@local"))).thenReturn(List.of(new BuiltinKnowledgeIndex.Pending("q",4,"护理")));
        when(embeddings.embed(anyList())).thenReturn(List.of());
        assertThat(new BuiltinKnowledgeIndex(jdbc,embeddings).reindex()).isZero();
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        var lex=new BuiltinKnowledgeSearch.Hit("a","QUESTION","护理","原文","curated","非真实面经",0,true,false);
        var vector=new BuiltinKnowledgeSearch.Hit("a","QUESTION","护理","原文","curated","非真实面经",0,false,true);
        var rank=BuiltinKnowledgeSearch.rank(List.of(lex),List.of(vector),10);
        assertThat(rank.items()).hasSize(1);assertThat(rank.items().get(0).lexicalMatch()).isTrue();assertThat(rank.items().get(0).vectorMatch()).isTrue();
        assertThat(rank.items().get(0).score()).isEqualTo((1+OccupationRrf.LEXICAL_WITH_VECTOR)/61);
        assertThat(BuiltinKnowledgeSearch.rank(List.of(lex),List.of(),10).vectorAvailable()).isFalse();
    }
    @Test void localIndexWritesUsingSourceRevisionAndFingerprintWithoutChangingContentVersion() {
        var jdbc=mock(JdbcTemplate.class);var embeddings=mock(OccupationEmbeddings.class);
        when(embeddings.fingerprint()).thenReturn("bge-m3@local");when(embeddings.configured()).thenReturn(true);
        when(jdbc.query(anyString(),any(RowMapper.class),eq("bge-m3@local"))).thenReturn(List.of(new BuiltinKnowledgeIndex.Pending("q",9,"内容")));
        var vector=new float[1024];vector[0]=1;
        when(embeddings.embed(List.of("内容"))).thenReturn(List.of(vector));
        when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        assertThat(new BuiltinKnowledgeIndex(jdbc,embeddings).reindex()).isEqualTo(1);
        verify(jdbc).update(contains("WHERE id=? AND revision=?"),anyString(),eq("bge-m3@local"),eq("q"),eq(9L));
    }
}
