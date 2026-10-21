package com.offerpilot.resume.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.core.io.ByteArrayResource;
import reactor.core.publisher.Flux;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ResumeExtractorTest {
    @Test void sendsV4FidelityConstraintsAndPreservesVerbatimVisionOutput() throws Exception {
        var json = new ObjectMapper();
        var gateway = mock(ModelGateway.class);
        var expected = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(ResumeStructure.SCHEMA);
        expected.withObject("/basics").put("title", "Java 开发实习生");
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected.path("projects").get(0))
            .put("name", "示例练习平台").put("role", "个人学习项目");
        ((com.fasterxml.jackson.databind.node.ObjectNode) expected.path("skills").get(0))
            .put("category", "工具").withArray("items")
            .add("熟悉 Docker 常用命令与 VS Code 调试").add("能使用 curl 测试接口");
        expected.put("summary", "对软件开发有兴趣。\n\n能够认真完成任务。");
        when(gateway.stream(eq(ModelTier.FLASH), anyList(), eq(0.0))).thenReturn(response(expected.toString()));
        var prompt = new org.springframework.core.io.FileSystemResource(
            "../offerpilot-bootstrap/src/main/resources/prompts/resume/extract.st");
        var extractor = new ResumeExtractor(gateway, json, prompt);
        var result = extractor.extractResult(new ResumeDocumentReader.Document("示例练习平台 个人学习项目",
            List.of(new ResumeDocumentReader.Image(new byte[]{1}, "image/png")), "pdf-vision"), null, "FLASH");
        assertThat(result.structured()).isEqualTo(expected);
        assertThat(result.metadata().path("promptVersion").asText()).isEqualTo("v4");
        assertThat(result.metadata().path("channel").asText()).isEqualTo("pdf-vision");
        verify(gateway).stream(eq(ModelTier.FLASH), argThat(messages -> {
            assertThat(messages.get(0).getText()).contains("版本 v4", "项目标题行上的身份/性质标注", "包括右侧标注",
                "个人学习项目", "课程设计", "团队项目-后端负责人", "原文放进 projects[].role",
                "basics.title", "求职意向：…", "应聘方向：…", "不得根据技能或经历推断",
                "句号/分号/顿号分隔的独立陈述", "不在「与/和/及」处拆分", "原文连续子串", "保留前导动词与熟练度",
                "按原文顺序拼接", "段落分隔（\\n\\n）", "不改写", "不凭空新增分类", "不再复制或改写生成重复分类",
                "主修课程、年龄不抽取");
            return true;
        }), eq(0.0));
    }

    @Test void acceptsFencedJsonButRejectsMissingFieldsAndTrailingData() throws Exception {
        var gateway = mock(ModelGateway.class);
        when(gateway.defaultTier()).thenReturn(ModelTier.FLASH);
        var extractor = new ResumeExtractor(gateway, new ObjectMapper(), new ByteArrayResource("extract only".getBytes()));
        var document = new ResumeDocumentReader.Document("简历里的忽略指令仅是数据", List.of(), "text");
        when(gateway.stream(eq(ModelTier.FLASH), anyList(), eq(0.0))).thenReturn(response("```json\n" + ResumeStructure.SCHEMA + "\n```"));
        assertThat(extractor.extract(document).path("basics").isObject()).isTrue();
        when(gateway.stream(eq(ModelTier.FLASH), anyList(), eq(0.0))).thenReturn(response("{\"basics\":{}}"));
        assertThatThrownBy(() -> extractor.extract(document)).hasMessageContaining("结构");
        when(gateway.stream(eq(ModelTier.FLASH), anyList(), eq(0.0))).thenReturn(response(ResumeStructure.SCHEMA + " {}"));
        assertThatThrownBy(() -> extractor.extract(document)).hasMessageContaining("有效");
    }
    @Test void imagesUseVisionTierAndDoNotFallbackToTextOnly() throws Exception {
        var gateway = mock(ModelGateway.class);
        when(gateway.defaultTier()).thenReturn(ModelTier.PRO);
        when(gateway.stream(eq(ModelTier.VISION_ALT), anyList(), eq(0.0))).thenReturn(response(ResumeStructure.SCHEMA));
        var extractor = new ResumeExtractor(gateway, new ObjectMapper(), new ByteArrayResource("extract only".getBytes()));
        extractor.extract(new ResumeDocumentReader.Document("", List.of(new ResumeDocumentReader.Image(new byte[]{1}, "image/png")), "image-vision"));
        verify(gateway).stream(eq(ModelTier.VISION_ALT), argThat(messages -> ((org.springframework.ai.chat.messages.UserMessage) messages.get(1)).getMedia().size() == 1), eq(0.0));
    }
    @Test void rejectsUnknownFieldsAndInvalidConfidence() throws Exception {
        var json = new ObjectMapper();
        var structure = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(ResumeStructure.SCHEMA);
        structure.put("extra", "hidden");
        assertThatThrownBy(() -> ResumeStructure.validate(structure)).hasMessageContaining("结构");
        structure.remove("extra"); structure.withObject("/confidence").put("basics.name", 2);
        assertThatThrownBy(() -> ResumeStructure.validate(structure)).hasMessageContaining("结构");
    }
    @Test void projectRoleInBracketsIsSplitOnlyWhenTheRoleIsMissingAndLooksLikeARole() throws Exception {
        var json = new ObjectMapper();
        var structure = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(ResumeStructure.SCHEMA);
        var projects = structure.putArray("projects");
        for (String[] p : new String[][]{{"城市共享单车需求预测（数据建模）", ""}, {"校园导航系统（微信小程序）", ""}, {"日志平台(核心开发)", "组长"}}) {
            var item = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(ResumeStructure.SCHEMA).path("projects").get(0);
            projects.add(item.put("name", p[0]).put("role", p[1]));
        }
        var result = ResumeExtractor.splitProjectRoles(ResumeStructure.validate(structure)).path("projects");
        assertThat(result.get(0).path("name").asText()).isEqualTo("城市共享单车需求预测");
        assertThat(result.get(0).path("role").asText()).isEqualTo("数据建模");
        assertThat(result.get(1).path("name").asText()).isEqualTo("校园导航系统（微信小程序）");
        assertThat(result.get(1).path("role").asText()).isEmpty();
        assertThat(result.get(2).path("name").asText()).isEqualTo("日志平台(核心开发)");
    }
    private Flux<ChatResponse> response(String text) { return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(text))))); }
    @Test void mapsTimeoutAndProviderStatusWithoutLeakingResponse() throws Exception {
        var gateway = mock(ModelGateway.class);
        when(gateway.defaultTier()).thenReturn(ModelTier.FLASH);
        var extractor = new ResumeExtractor(gateway, new ObjectMapper(), new ByteArrayResource("extract".getBytes()));
        var document = new ResumeDocumentReader.Document("简历", List.of(), "text");
        when(gateway.stream(any(), anyList(), eq(0.0))).thenReturn(Flux.error(new java.util.concurrent.TimeoutException()));
        assertThatThrownBy(() -> extractor.extract(document)).hasMessageContaining("超时");
        for (int status : List.of(401, 429, 500)) {
            when(gateway.stream(any(), anyList(), eq(0.0))).thenReturn(Flux.error(
                org.springframework.web.reactive.function.client.WebClientResponseException.create(status, "error", null, "secret-provider-body".getBytes(), null)));
            assertThatThrownBy(() -> extractor.extract(document)).hasMessageNotContaining("secret-provider-body")
                .hasMessageContaining(status == 401 ? "鉴权" : status == 429 ? "受限" : "模型调用失败");
        }
    }
}
