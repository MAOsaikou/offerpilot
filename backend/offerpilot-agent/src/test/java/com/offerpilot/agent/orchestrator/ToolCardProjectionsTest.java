package com.offerpilot.agent.orchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.experts.ExpertResult;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
class ToolCardProjectionsTest {
    ObjectMapper json=new ObjectMapper();
    @Test void projectsRadarCalendarAndScoresOnlyFromToolFields() throws Exception {
        var result=ToolCardProjections.expand(ExpertResult.cards("ok",
                new ExpertResult.Card("g","skill_gap",json.readTree("{\"skills\":[{\"name\":\"Java\",\"current\":2,\"required\":5}]}")),
                new ExpertResult.Card("j","job_card",json.readTree("{\"items\":[{\"title\":\"开发\",\"company\":\"公司\",\"deadline\":\"2026-12-01\",\"url\":\"https://example.test\"},{\"deadline\":null}]}")),
                new ExpertResult.Card("i","interview_report",json.readTree("{\"turns\":[{\"score\":null,\"feedback\":\"未评分\",\"star\":{}}]}"))));
        assertThat(result.cards()).hasSize(6);
        assertThat(result.cards()).allSatisfy(card -> assertThat(com.offerpilot.conversation.application.MessageCards.parse(card.cardId(),card.cardType(),card.payload(),json)).isNotNull());
        assertThat(json.valueToTree(result.cards().get(3).payload()).at("/axes/0/current").asInt()).isEqualTo(2);
        assertThat(json.valueToTree(result.cards().get(4).payload()).path("items")).hasSize(1);
        assertThat(json.valueToTree(result.cards().get(5).payload()).path("score").isNull()).isTrue();
        assertThat(ToolCardProjections.expand(ExpertResult.text("没有结果")).cards()).isEmpty();
    }
    @Test void citationIndexesAreAssignedByProgramWithoutInventingRetrievalScores() throws Exception {
        var index=new AtomicInteger();
        var cards=List.of(new ExpertResult.Card("k","knowledge_note_citations",json.readTree("{\"items\":[{\"noteId\":\"n\",\"title\":\"来源\",\"excerpt\":\"原文\"},{\"noteId\":\"n\",\"title\":\"来源\",\"excerpt\":\"原文\"}]}")));
        var first=CardCitations.read(cards,index);
        assertThat(first).hasSize(1);assertThat(first.get(0).index()).isEqualTo(1);assertThat(first.get(0).score()).isNull();
        assertThat(CardCitations.read(cards,index).get(0).index()).isEqualTo(2);
    }
    @Test void citationScoresArePassedThroughFromRetrievalAndSimilarity() throws Exception {
        var index=new AtomicInteger();
        var cards=List.of(
                new ExpertResult.Card("k","knowledge_note_citations",new ObjectMapper().valueToTree(java.util.Map.of("items",List.of(
                        new com.offerpilot.knowledge.application.KnowledgeNotesForChat.Citation("n1","缓存笔记","技术积累","热点key过期","KB-1",0.0328))))),
                new ExpertResult.Card("c","career_recommendation",json.readTree("{\"recommendations\":["
                        +"{\"similarity\":0.87,\"citations\":[{\"occupationId\":\"o1\",\"title\":\"后端工程师\",\"excerpt\":\"设计服务\"}]},"
                        +"{\"similarity\":\"bad\",\"citations\":[{\"occupationId\":\"o2\",\"title\":\"测试工程师\",\"excerpt\":\"编写用例\"}]}]}")));
        var sources=CardCitations.read(cards,index);
        assertThat(sources).extracting(s -> s.collection()+":"+s.docId()+":"+s.score())
                .containsExactly("notes:n1:0.0328","occupations:o1:0.87","occupations:o2:null");
    }
}
