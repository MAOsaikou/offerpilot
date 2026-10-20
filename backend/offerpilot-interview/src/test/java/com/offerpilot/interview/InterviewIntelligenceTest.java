package com.offerpilot.interview;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.offerpilot.interview.application.*;
import com.offerpilot.common.error.*;
import com.offerpilot.common.text.NumericProvenance;
import com.offerpilot.llm.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import reactor.core.publisher.Flux;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class InterviewIntelligenceTest {
    final ObjectMapper json=new ObjectMapper();
    @SuppressWarnings("unchecked") final ObjectProvider<ModelGateway> providers=mock(ObjectProvider.class);
    final ModelGateway gateway=mock(ModelGateway.class);
    InterviewIntelligence model() throws Exception {
        return new InterviewIntelligence(providers,json,new ByteArrayResource("跨行业 role industry options.questionCount".getBytes()),new ByteArrayResource("评分引用".getBytes()),new ByteArrayResource("报告".getBytes()));
    }
    InterviewOptions options(int count,Map<String,Integer> mix) {return new InterviewOptions(null,null,"HARD","TEXT",30,count,mix,"医疗","IMMEDIATE");}
    @Test void validatesCountDifficultyAndExplicitMixIncludingZeroAndUnknownTypes() {
        for(int count:List.of(3,8,15)) options(count,Map.of()).validate();
        for(int count:List.of(2,16,-1)) assertThatThrownBy(() -> options(count,Map.of()).validate()).isInstanceOf(BusinessException.class);
        for(var mix:List.of(Map.of("CASE",2),Map.of("UNKNOWN",3),Map.of("CASE",-1,"KNOWLEDGE",4)))
            assertThatThrownBy(() -> options(3,mix).validate()).isInstanceOf(BusinessException.class);
        options(3,Map.of("CASE",3,"KNOWLEDGE",0)).validate();
    }
    ObjectNode scoreOutput() {
        var node=json.createObjectNode().put("rationale","说明了本人行动，结果证据待补充");
        var dimensions=node.putObject("dimensions");
        for(String name:InterviewIntelligence.DIMENSIONS) dimensions.putObject(name).put("score",80).put("rationale","本人采取了具体行动").put("evidence","我负责护理宣教");
        node.putArray("strengths").add("职责明确");node.putArray("weaknesses").add("补充验证方式");
        node.putArray("improvedAnswerParts").addObject().put("section","行动").put("quote","我负责护理宣教").put("placeholder","【补充提升99%的证据】");
        return node;
    }
    @Test void scoreNeedsAnswerEvidenceAndExactFactQuotesAndStripsUnsupportedArabicHanAndFullwidthNumbers() throws Exception {
        var model=model();var output=scoreOutput();
        var saved=model.validateScore(output,"我负责护理宣教","");
        assertThat(saved.path("score").asInt()).isEqualTo(80);
        assertThat(saved.path("improvedAnswer").asText()).contains("我负责护理宣教","【补充你的具体数据】").doesNotContain("99");
        for(String bad:List.of("我负责护理宣教并提升99%","我在其他医院任职")) {
            var tampered=scoreOutput();((ObjectNode)tampered.path("improvedAnswerParts").get(0)).put("quote",bad);
            assertThatThrownBy(() -> model.validateScore(tampered,"我负责护理宣教","")).isInstanceOf(BusinessException.class);
        }
        for(int bad:List.of(-1,101)) {
            var tampered=scoreOutput();((ObjectNode)tampered.path("dimensions").path("logic")).put("score",bad);
            assertThatThrownBy(() -> model.validateScore(tampered,"我负责护理宣教","")).isInstanceOf(BusinessException.class);
        }
        var wrong=scoreOutput();((ObjectNode)wrong.path("dimensions").path("logic")).put("evidence","未说过的证据");
        assertThatThrownBy(() -> model.validateScore(wrong,"我负责护理宣教","")).isInstanceOf(BusinessException.class);
        assertThat(NumericProvenance.stripUnsupported("提升２５％，三个人，一年，30%","30%")).isEqualTo("提升【补充你的具体数据】，\u3010补充你的具体数据】人，【补充你的具体数据】，30%");
    }
    @Test void guidanceStylePlaceholderIsNormalizedInsteadOfFailingScore() throws Exception {
        var output=scoreOutput();((ObjectNode)output.path("improvedAnswerParts").get(0)).put("placeholder","【从分离焦虑和陌生环境角度分析可能原因】");
        assertThat(model().validateScore(output,"我负责护理宣教","").path("improvedAnswer").asText()).contains("【补充：从分离焦虑和陌生环境角度分析可能原因】");
        var nested=scoreOutput();((ObjectNode)nested.path("improvedAnswerParts").get(0)).put("placeholder","【一】【二】");
        assertThatThrownBy(() -> model().validateScore(nested,"我负责护理宣教","")).isInstanceOf(BusinessException.class);
    }
    @Test void structuredQuestionsRejectWrongCountDifficultyMixAndDuplicateQuestions() throws Exception {
        var model=model(); var output=json.createObjectNode();var list=output.putArray("questions");
        for(int i=0;i<3;i++) list.addObject().put("text","护理场景的判断问题"+i).put("type","CASE").put("difficulty","HARD");
        assertThat(model.validateQuestions(output,options(3,Map.of("CASE",3)))).hasSize(3);
        assertThatThrownBy(() -> model.validateQuestions(output,options(4,Map.of()))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> model.validateQuestions(output,options(3,Map.of("BEHAVIORAL",3)))).isInstanceOf(BusinessException.class);
        ((ObjectNode)list.get(0)).put("difficulty","EASY");
        assertThatThrownBy(() -> model.validateQuestions(output,options(3,Map.of()))).isInstanceOf(BusinessException.class);
    }
    @Test void nonTechPromptUsesRoleIndustryResumeJdAndRetriesInvalidOutput() throws Exception {
        when(providers.getIfAvailable()).thenReturn(gateway);
        var output=json.createObjectNode();var list=output.putArray("questions");
        for(int i=0;i<3;i++) list.addObject().put("text","护士如何处理宣教场景"+i).put("type","CASE").put("difficulty","HARD");
        when(gateway.stream(eq(ModelTier.FLASH),anyList(),eq(0.3))).thenReturn(response("{}"),response(output.toString()));
        var session=new InterviewService.Session("iv","护士","综合",0,false,"","",List.of(),null,options(3,Map.of("CASE",3)),"pending");
        assertThat(model().questions("owner",session,"我负责护理宣教","病区患者教育")).hasSize(3);
        var captor=org.mockito.ArgumentCaptor.forClass(List.class);verify(gateway,times(2)).stream(eq(ModelTier.FLASH),captor.capture(),eq(0.3));
        assertThat(captor.getAllValues().get(0).toString()).contains("护士","医疗","病区患者教育","我负责护理宣教","questionCount").doesNotContain("Java");
    }
    Flux<ChatResponse> response(String text) {return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));}
    JsonNode liveFailures() throws Exception {
        try(var input=getClass().getResourceAsStream("/fixtures/interview/cross-industry-failures.json")) {
            return json.readTree(Objects.requireNonNull(input));
        }
    }
    @TestFactory List<DynamicTest> replaysEveryRejectedLiveAttemptWithoutPromotingQuestionOrJdToExperience() throws Exception {
        var tests=new ArrayList<DynamicTest>();
        for(var fixture:liveFailures()) tests.add(DynamicTest.dynamicTest("attempt-"+fixture.path("call")+" "+fixture.path("role").asText(),() -> {
            var model=model();var output=fixture.path("output");
            if (fixture.path("kind").asText().equals("report")) {
                var saved=model.validateNarrative(output);
                for(String key:List.of("strengths","weaknesses","advice"))
                    assertThat(saved.path(key).size()).isEqualTo(Math.min(8,output.path(key).size()));
            } else if (fixture.path("expected").asText().equals("accept")) {
                var saved=model.validateScore(output,fixture.path("answer").asText(),"");
                assertThat(saved.path("source").asText()).isEqualTo("model");
                assertThat(saved.path("improvedAnswer").asText()).contains("【");
            } else {
                assertThatThrownBy(() -> model.validateScore(output,fixture.path("answer").asText(),""))
                        .isInstanceOf(BusinessException.class).hasMessageContaining("quote");
            }
        }));
        return tests;
    }
    @Test void repairUsesSpecificFieldAndPreviousOutputAndStopsAfterOneRetry() throws Exception {
        when(providers.getIfAvailable()).thenReturn(gateway);
        var fixture=liveFailures().get(0);String answer=fixture.path("answer").asText();
        var repaired=(ObjectNode)fixture.path("output").deepCopy();
        for(var part:repaired.path("improvedAnswerParts")) {
            if (!answer.contains(part.path("quote").asText()))
                ((ObjectNode)part).put("quote","").put("placeholder","【补充真实背景与职责】");
        }
        var session=new InterviewService.Session("iv","护士","综合",0,false,"","",List.of(),null,options(3,Map.of()),"model");
        var turn=new InterviewService.Answer(0,"题干假设场景",answer,null,null,null,"CASE","HARD","model",null);
        when(gateway.stream(eq(ModelTier.FLASH),anyList(),eq(0.3)))
                .thenReturn(response(fixture.path("output").toString()),response(repaired.toString()));
        assertThat(model().score("owner",session,turn,"","").path("source").asText()).isEqualTo("model");
        var captor=org.mockito.ArgumentCaptor.forClass(List.class);
        verify(gateway,times(2)).stream(eq(ModelTier.FLASH),captor.capture(),eq(0.3));
        assertThat(captor.getAllValues().get(1).toString()).contains("validationError","previousOutput","improvedAnswerParts[0].quote","不得引用question/JD");
        reset(gateway);
        when(gateway.stream(eq(ModelTier.FLASH),anyList(),eq(0.3))).thenReturn(response(fixture.path("output").toString()));
        assertThatThrownBy(() -> model().score("owner",session,turn,"","")).isInstanceOf(BusinessException.class);
        verify(gateway,times(2)).stream(eq(ModelTier.FLASH),anyList(),eq(0.3));
    }
    @Test void rejectsMalformedNarrativeAndDoesNotHideInvalidEntriesBeyondDisplayLimit() throws Exception {
        var model=model();var output=json.createObjectNode();output.putArray("strengths");output.putArray("weaknesses");output.putArray("advice").add("重新练习");
        assertThat(model.validateNarrative(output).path("strengths").isEmpty()).isTrue();
        var advice=(ArrayNode)output.path("advice");advice.removeAll();
        assertThatThrownBy(() -> model.validateNarrative(output)).isInstanceOf(BusinessException.class);
        for(int i=0;i<9;i++) advice.add("练习"+i);
        advice.add(123);
        assertThatThrownBy(() -> model.validateNarrative(output)).isInstanceOf(BusinessException.class);
        advice.removeAll();for(int i=0;i<65;i++) advice.add("练习"+i);
        assertThatThrownBy(() -> model.validateNarrative(output)).isInstanceOf(BusinessException.class);
    }
    @Test void truncatedJsonIsRetriedOnceAndStillRejectedWithoutFabricatingScore() throws Exception {
        when(providers.getIfAvailable()).thenReturn(gateway);
        when(gateway.stream(eq(ModelTier.FLASH),anyList(),eq(0.3))).thenReturn(response("{\"strengths\":["));
        assertThatThrownBy(() -> model().narrative("owner",null,json.createObjectNode())).isInstanceOf(BusinessException.class);
        verify(gateway,times(2)).stream(eq(ModelTier.FLASH),anyList(),eq(0.3));
    }
    @Test void placeholderNormalizationStillRejectsFabricatedEvidenceTypesAndUnboundedSuggestions() throws Exception {
        var model=model();
        for(String placeholder:List.of("【改为：核实２５人后再报告】","【改写为：核实三个人后再报告】")) {
            var output=scoreOutput();((ObjectNode)output.path("improvedAnswerParts").get(0)).put("placeholder",placeholder);
            assertThat(model.validateScore(output,"我负责护理宣教","").path("improvedAnswer").asText()).contains("【补充你的具体数据】");
        }
        for(String placeholder:List.of("直接声称我已完成核验","【补充【虚构】】","【改为"+"长".repeat(201)+"】")) {
            var output=scoreOutput();((ObjectNode)output.path("improvedAnswerParts").get(0)).put("placeholder",placeholder);
            assertThatThrownBy(() -> model.validateScore(output,"我负责护理宣教","")).isInstanceOf(BusinessException.class);
        }
        var output=scoreOutput();((ObjectNode)output.path("improvedAnswerParts").get(0)).put("quote",123);
        assertThatThrownBy(() -> model.validateScore(output,"我负责护理宣教","")).isInstanceOf(BusinessException.class);
        var decimal=scoreOutput();((ObjectNode)decimal.path("dimensions").path("logic")).put("score",50.5);
        assertThatThrownBy(() -> model.validateScore(decimal,"我负责护理宣教","")).isInstanceOf(BusinessException.class);
    }
    @Test void aggregationAveragesOnlyRealScoresAndLeavesUnavailableOrSkippedNull() throws Exception {
        var evaluation=model().validateScore(scoreOutput(),"我负责护理宣教","");
        var second=evaluation.deepCopy(); second.put("score",55);
        int[] dimensionScores={100,60,40,20};
        for(int i=0;i<4;i++) ((ObjectNode)second.path("dimensions").path(InterviewIntelligence.DIMENSIONS.get(i))).put("score",dimensionScores[i]);
        var answers=List.of(new InterviewService.Answer(0,"护理场景","我负责护理宣教",null,null,null,"CASE","HARD","model",evaluation),
                new InterviewService.Answer(1,"另一场景","我负责护理宣教",null,null,null,"CASE","HARD","model",second),
                new InterviewService.Answer(2,"跳过题","",null,null,null,"CASE","HARD","rule",model().unavailable("已跳过")));
        var session=new InterviewService.Session("iv","护士","综合",0,true,"","",answers,null,options(3,Map.of()),"model");
        var report=InterviewGenerationService.aggregate(session);
        assertThat(report.overall()).isEqualTo(67); assertThat(report.dimensions()).isEqualTo(new InterviewRubric.Dimensions(90,70,60,50));
        assertThat(report.turns().get(2).score()).isNull();assertThat(report.note()).contains("2/3");assertThat(report.recommendedLearning()).isNotEmpty();
        assertThat(InterviewGenerationService.aggregate(new InterviewService.Session("iv","护士","综合",0,true,"","",List.of(answers.get(2)),null,options(3,Map.of()),"rule")).overall()).isNull();
    }
}
