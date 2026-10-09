package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class JobMatcherTest {
    private final ObjectMapper json=new ObjectMapper();
    private ObjectNode report() throws Exception {
        return (ObjectNode)json.readTree("""
            {"summary":"项目证据可用", "requirements":[{"requirement":"Java","status":"MET","jdQuote":"熟悉Java","resumePath":"/summary","resumeQuote":"Java项目","reason":"有相关项目","suggestion":"补充项目细节"}]}
            """);
    }
    @Test void acceptsOnlyExactQuotesAtStringPaths() throws Exception {
        var resume=json.readTree("{\"summary\":\"完成Java项目\"}");
        assertThatCode(()->JobMatcher.validate(report(),"熟悉Java及SQL",resume)).doesNotThrowAnyException();
        var wrong=report(); ((ObjectNode)wrong.path("requirements").get(0)).put("resumeQuote","大型分布式系统");
        assertThatThrownBy(()->JobMatcher.validate(wrong,"熟悉Java",resume)).isInstanceOf(BusinessException.class);
        var path=report(); ((ObjectNode)path.path("requirements").get(0)).put("resumePath","/missing");
        assertThatThrownBy(()->JobMatcher.validate(path,"熟悉Java",resume)).isInstanceOf(BusinessException.class);
    }
    @Test void rejectsInventedRequirementsAndUnbackedScores() throws Exception {
        var resume=json.readTree("{\"summary\":\"完成Java项目\"}");
        assertThatThrownBy(()->JobMatcher.validate(report(),"熟悉Python",resume)).isInstanceOf(BusinessException.class);
        var missing=report(); var item=(ObjectNode)missing.path("requirements").get(0); item.put("status","MISSING");
        assertThatThrownBy(()->JobMatcher.validate(missing,"熟悉Java",resume)).isInstanceOf(BusinessException.class);
        item.put("resumePath","").put("resumeQuote","");
        assertThatCode(()->JobMatcher.validate(missing,"熟悉Java",resume)).doesNotThrowAnyException();
    }
    @Test void duplicateEvidenceCannotInflateCoverage() throws Exception {
        var report=report(); ((com.fasterxml.jackson.databind.node.ArrayNode)report.path("requirements")).add(report.path("requirements").get(0).deepCopy());
        JobMatcher.validate(report,"熟悉Java",json.readTree("{\"summary\":\"Java项目\"}"));
        assertThat(report.path("requirements")).hasSize(1);
    }
    @Test void canonicalizesFormattingOnlyAndRetainsExactSourceSubstrings() throws Exception {
        var report=report();
        var item=(ObjectNode)report.path("requirements").get(0);
        item.put("jdQuote","熟悉Java;了解SQL").put("resumeQuote","Java项目(课程)");
        String jd="要求：熟悉 Java；了解 SQL。";
        var resume=json.readTree("{\"summary\":\"完成 Java 项目（课程）\"}");
        JobMatcher.validate(report,jd,resume);
        assertThat(item.path("resumeQuote").asText()).isEqualTo("Java项目(课程)");
        var saved=report.path("requirements").get(0);
        assertThat(saved.path("jdQuote").asText()).isEqualTo("熟悉 Java；了解 SQL");
        assertThat(saved.path("resumeQuote").asText()).isEqualTo("Java 项目（课程）");
        assertThat(jd).contains(saved.path("jdQuote").asText());
        assertThat(resume.at(saved.path("resumePath").asText()).asText()).contains(saved.path("resumeQuote").asText());
        assertThat(JobMatcher.canonicalQuote("Java SQL", "java SQL")).isNull();
        assertThat(JobMatcher.canonicalQuote("Java，SQL", "Java SQL")).isNull();
        assertThat(JobMatcher.canonicalQuote("Java 和 SQL", "JavaSQL")).isNull();
        assertThat(JobMatcher.canonicalQuote("Java", " ")).isNull();
    }

    @Test void deepSeekLikeRepeatedCombinedJdAndInvalidLeavesDoNotLoseVerifiedItems() throws Exception {
        var report=report(); var items=(com.fasterxml.jackson.databind.node.ArrayNode)report.path("requirements");
        var first=(ObjectNode)items.get(0); first.put("jdQuote","掌握Java与SpringBoot");
        items.add(first.deepCopy().put("requirement","Spring Boot")); // DeepSeek repeats combined JD for split skills.
        items.add(first.deepCopy().put("jdQuote","理解MySQL").put("resumePath","/skills/0/items")); // array, not string
        items.add(first.deepCopy().put("jdQuote","了解Redis").put("resumePath","projects[0].bullets[0]"));
        items.add(first.deepCopy().put("jdQuote","使用Git").put("status","MISSING")); // evidence must be empty
        first.put("unverifiedQuote","虚构扩展字段"); report.put("score",100);
        JobMatcher.validate(report,"掌握 Java 与 Spring Boot；理解MySQL；了解Redis；使用Git",json.readTree("{\"summary\":\"Java项目\"}"));
        assertThat(report.path("requirements")).hasSize(1);
        assertThat(report.path("requirements").get(0).has("unverifiedQuote")).isFalse();
        assertThat(report.has("score")).isFalse();
        assertThat(report.path("summary").asText()).contains("未通过校验或重复");
    }

    @Test void supportsEscapedPointersAndRejectsQuoteLabelsAndStitchedLeaves() throws Exception {
        var report=report(); var item=(ObjectNode)report.path("requirements").get(0);
        item.put("resumePath","/a~1b~0c");
        JobMatcher.validate(report,"熟悉Java",json.readTree("{\"a/b~c\":\"Java项目\"}"));
        item=(ObjectNode)report.path("requirements").get(0); item.put("resumeQuote","技能：Java项目");
        assertThatThrownBy(()->JobMatcher.validate(report,"熟悉Java",json.readTree("{\"a/b~c\":\"Java项目\"}"))).isInstanceOf(BusinessException.class);
    }

    @Test void retriesOnlyEntirelyInvalidReportsAndComputesScoreFromVerifiedItems() throws Exception {
        var gateway=org.mockito.Mockito.mock(com.offerpilot.llm.ModelGateway.class);
        org.mockito.Mockito.when(gateway.defaultTier()).thenReturn(com.offerpilot.llm.ModelTier.FLASH);
        org.mockito.Mockito.when(gateway.modelName(org.mockito.ArgumentMatchers.any())).thenReturn("stub");
        var valid=report(); var invalid=report(); ((ObjectNode)invalid.path("requirements").get(0)).put("resumeQuote","编造经历");
        ((com.fasterxml.jackson.databind.node.ArrayNode)valid.path("requirements")).add(invalid.path("requirements").get(0));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.when(gateway.stream(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.eq(0.0)))
            .thenAnswer(invocation->{
                var messages=invocation.<java.util.List<org.springframework.ai.chat.messages.Message>>getArgument(1);
                int call=calls.incrementAndGet();
                if(call==2) assertThat(messages.get(messages.size()-1).getText()).contains("原文依据校验", "未保存");
                String raw=call==1?invalid.toString():"```json\n"+valid+"\n```";
                return reactor.core.publisher.Flux.just(new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(
                    new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage(raw)))));
            });
        var matcher=new JobMatcher(gateway,json,new org.springframework.core.io.ByteArrayResource("rules".getBytes()));
        var result=matcher.match(json.readTree("{\"requirement\":\"熟悉Java\"}"),json.readTree("{\"summary\":\"Java项目\"}"));
        assertThat(calls).hasValue(2);
        assertThat(result.path("requirements")).hasSize(1);
        assertThat(result.path("score").asInt()).isEqualTo(100);
        org.mockito.Mockito.when(gateway.stream(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.eq(0.0)))
            .thenReturn(reactor.core.publisher.Flux.just(new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(
                new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("not JSON"))))));
        org.mockito.Mockito.clearInvocations(gateway);
        assertThatThrownBy(()->matcher.match(json.readTree("{\"requirement\":\"熟悉Java\"}"),json.createObjectNode())).isInstanceOf(BusinessException.class);
        org.mockito.Mockito.verify(gateway,org.mockito.Mockito.times(2)).stream(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.eq(0.0));
    }
    @Test void limitsVerifiedRowsAndNeverSavesBlankOrOverlongCanonicalEvidence() throws Exception {
        var report=report(); var items=(com.fasterxml.jackson.databind.node.ArrayNode)report.path("requirements");
        items.removeAll(); var jd=new StringBuilder();
        for(int i=0;i<13;i++) {
            jd.append("要求").append(i).append(";");
            var row=report().path("requirements").get(0).deepCopy();
            ((ObjectNode)row).put("jdQuote","要求"+i+";"); items.add(row);
        }
        JobMatcher.validate(report,jd.toString(),json.readTree("{\"summary\":\"Java项目\"}"));
        assertThat(report.path("requirements")).hasSize(12);
        assertThat(JobMatcher.canonicalQuote("前\u00a0后","\u00a0")).isNull();
        var oversized=report(); ((ObjectNode)oversized.path("requirements").get(0)).put("jdQuote","熟悉Java");
        assertThatThrownBy(()->JobMatcher.validate(oversized,"熟悉"+" ".repeat(1600)+"Java",json.readTree("{\"summary\":\"Java项目\"}")))
                .isInstanceOf(BusinessException.class);
    }
}
