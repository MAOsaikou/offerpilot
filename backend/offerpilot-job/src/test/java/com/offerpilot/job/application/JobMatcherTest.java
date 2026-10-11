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
        assertThatThrownBy(()->JobMatcher.validate(wrong,"熟悉Java",resume)).isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.recoverable()).isTrue());
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
        assertThat(JobMatcher.canonicalQuote("善于交流，有良好的团队合作精神和协调沟通能力。", "善于交流，有良好的团队合作精神和协调沟通能力；"))
                .isEqualTo("善于交流，有良好的团队合作精神和协调沟通能力");
        assertThat(JobMatcher.canonicalQuote("Java、C、C++、Python、Golang等，了解MySQL；\n熟练使用SQL", "Java、C、C++、Python、Golang等,了解MySQL; 熟练使用SQL；"))
                .isEqualTo("Java、C、C++、Python、Golang等，了解MySQL；\n熟练使用SQL");
        assertThat(JobMatcher.canonicalQuote("Java、C、C++、Python", "Java、Python")).isNull();
        String preferred = "1. 有参与各级计算机竞赛并获奖经历；\n2. 有原创的技术博客或开源项目；\n3. 有大模型应用开发经验，如使用LangChain/LlamaIndex等框架构建RAG系统、开发AI Agent应用等；";
        assertThat(JobMatcher.canonicalQuote(preferred, "有参与各级计算机竞赛并获奖经历；有原创的技术博客或开源项目；有大模型应用开发经验，如使用LangChain/LlamaIndex等框架构建RAG系统、开发AI Agent应用等"))
                .isEqualTo(preferred.substring(3, preferred.length()-1));
        assertThat(JobMatcher.canonicalQuote("修复12个线上缺陷", "修复个线上缺陷")).isNull();
        assertThat(JobMatcher.canonicalQuote("“Java” 项目", "\"Java\"项目")).isEqualTo("“Java” 项目");
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
        assertThat(report.path("requirements")).hasSize(2);
        assertThat(report.path("requirements").get(0).has("unverifiedQuote")).isFalse();
        assertThat(report.has("score")).isFalse();
        assertThat(report.path("summary").asText()).contains("未核实", "不计分");
    }


    @Test void enumeratedSkillsSurviveSharedQuotesAndScoreIgnoresModelNumbers() throws Exception {
        String jd = "熟悉Java、Spring Boot、MySQL、Redis；了解分布式与消息队列；有高并发项目经验优先。";
        String text = "基于Spring Boot和Redis的秒杀系统；Kafka日志管道；熟悉Java、MySQL、Docker。";
        var output = json.createObjectNode().put("summary", "逐项评估").put("score", 1);
        var items = output.putArray("requirements");
        for (String skill : java.util.List.of("Java", "Spring Boot", "MySQL", "Redis", "消息队列", "分布式", "高并发")) {
            String status = skill.equals("分布式") ? "MISSING" : skill.equals("高并发") ? "PARTIAL" : "MET";
            String quote = java.util.Set.of("消息队列", "分布式").contains(skill) ? "了解分布式与消息队列"
                    : skill.equals("高并发") ? "有高并发项目经验优先" : "熟悉Java、Spring Boot、MySQL、Redis";
            items.addObject().put("requirement", skill).put("status", status).put("jdQuote", quote)
                    .put("resumePath", status.equals("MISSING") ? "" : "/rawText")
                    .put("resumeQuote", status.equals("MISSING") ? "" : text)
                    .put("reason", "比较原文证据").put("suggestion", "准备真实项目细节");
        }
        // Same requirement with different case/spacing is a duplicate; different skills are not.
        items.add(items.get(1).deepCopy());
        ((ObjectNode)items.get(7)).put("requirement", " spring boot ");
        var gateway = org.mockito.Mockito.mock(com.offerpilot.llm.ModelGateway.class);
        org.mockito.Mockito.when(gateway.timeout(org.mockito.ArgumentMatchers.any())).thenReturn(java.time.Duration.ofSeconds(60));
        org.mockito.Mockito.when(gateway.modelName(org.mockito.ArgumentMatchers.any())).thenReturn("stub");
        org.mockito.Mockito.when(gateway.stream(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.eq(0.0)))
                .thenReturn(reactor.core.publisher.Flux.just(new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(
                        new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage(output.toString()))))));
        var matcher = new JobMatcher(gateway, json, new org.springframework.core.io.ByteArrayResource("rules".getBytes()));
        var result = matcher.match(json.createObjectNode().put("requirement", jd), json.createObjectNode().put("rawText", text), com.offerpilot.llm.ModelTier.FLASH);
        assertThat(result.path("requirements")).hasSize(7);
        assertThat(result.path("requirements")).extracting(n -> n.path("requirement").asText())
                .containsExactly("Java", "Spring Boot", "MySQL", "Redis", "消息队列", "分布式", "高并发");
        assertThat(result.path("score").asInt()).isEqualTo(79); // (5 MET + 0.5 PARTIAL) / 7
        assertThat(result.path("promptVersion").asText()).isEqualTo("job-match-v3");
        org.mockito.Mockito.verify(gateway, org.mockito.Mockito.times(1)).stream(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.eq(0.0));
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
        org.mockito.Mockito.when(gateway.timeout(org.mockito.ArgumentMatchers.any())).thenReturn(java.time.Duration.ofSeconds(60));
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
        // Too few verified rows get one repair; a worse repair must not destroy good evidence.
        var partial = report();
        var partialRows = (com.fasterxml.jackson.databind.node.ArrayNode) partial.path("requirements");
        for (int i = 0; i < 3; i++) partialRows.add(invalid.path("requirements").get(0).deepCopy());
        org.mockito.Mockito.clearInvocations(gateway);
        org.mockito.Mockito.when(gateway.stream(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.eq(0.0)))
                .thenReturn(reactor.core.publisher.Flux.just(new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(
                        new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage(partial.toString()))))),
                        reactor.core.publisher.Flux.just(new org.springframework.ai.chat.model.ChatResponse(java.util.List.of(
                                new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("not JSON"))))));
        var preserved = matcher.match(json.readTree("{\"requirement\":\"熟悉Java\"}"), json.readTree("{\"summary\":\"Java项目\"}"));
        assertThat(preserved.path("score").asInt()).isEqualTo(100);
        assertThat(preserved.path("unverifiedCount").asInt()).isEqualTo(3);
        assertThat(preserved.path("unverifiedRequirements").toString()).doesNotContain("编造经历");
        org.mockito.Mockito.verify(gateway,org.mockito.Mockito.times(2)).stream(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.eq(0.0));
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
            ((ObjectNode)row).put("requirement","要求"+i).put("jdQuote","要求"+i+";"); items.add(row);
        }
        JobMatcher.validate(report,jd.toString(),json.readTree("{\"summary\":\"Java项目\"}"));
        assertThat(report.path("requirements")).hasSize(13);
        items = (com.fasterxml.jackson.databind.node.ArrayNode) report.path("requirements");
        items.remove(12);
        JobMatcher.validate(report,jd.toString(),json.readTree("{\"summary\":\"Java项目\"}"));
        assertThat(report.path("requirements")).hasSize(12);
        assertThat(JobMatcher.canonicalQuote("前\u00a0后","\u00a0")).isNull();
        var oversized=report(); ((ObjectNode)oversized.path("requirements").get(0)).put("jdQuote","熟悉Java");
        assertThatThrownBy(()->JobMatcher.validate(oversized,"熟悉"+" ".repeat(1600)+"Java",json.readTree("{\"summary\":\"Java项目\"}")))
                .isInstanceOf(BusinessException.class);
    }
}
