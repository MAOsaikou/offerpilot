package com.offerpilot.resume.application;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.common.error.*;
import com.offerpilot.llm.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.core.io.ByteArrayResource;
import reactor.core.publisher.Flux;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResumeOptimizerTest {
    final ObjectMapper json=new ObjectMapper();
    static final String JD="熟悉Java与缓存优化；具备后端服务开发能力。";
    static final String REPORT="""
        {"summary":"突出已有后端贡献","patches":[{"id":"p1","op":"replace","path":"/summary","from":"负责后端开发","value":"参与后端服务开发","reason":"明确职责范围","jdEvidence":"后端服务开发","needsUserConfirmation":false}]}
        """;
    ObjectNode source() throws Exception {
        ObjectNode source=(ObjectNode)json.readTree(ResumeStructure.SCHEMA);source.put("summary","负责后端开发");
        source.withObject("/basics").put("phone","13800138000").put("email","private@example.test");return source;
    }
    @Test void rejectsInventedNumbersPointersQuotesAndExtraFields() throws Exception {
        for(String invalid:List.of(REPORT.replace("参与后端服务开发","独立实现12个接口"),REPORT.replace("后端服务开发\",\"needs","不存在的JD引文\",\"needs"),
            REPORT.replace("/summary","/basics/phone"),REPORT.replace("负责后端开发","伪造的原文"),REPORT.replace("\"patches\":","\"score\":99,\"patches\":")))
            assertThatThrownBy(() -> ResumeOptimizationValidator.validate(json.readTree(invalid),source(),JD)).hasMessageContaining("未通过校验");
        var source=source();source.put("summary","优化10个接口");
        var report=(ObjectNode)json.readTree(REPORT);var patch=(ObjectNode)report.path("patches").get(0);patch.put("from","优化10个接口");
        for(String text:List.of("优化10%接口","优化100个接口","优化１０个接口","效率提高百分之六十","领导三人团队")) {
            patch.put("value",text);
            assertThatThrownBy(() -> ResumeOptimizationValidator.validate(report,source,JD)).hasMessageContaining("未通过校验");
        }
        patch.put("value","改进10个接口的缓存策略");ResumeOptimizationValidator.validate(report,source,JD);
    }
    @Test void usesSelectedModelOmitsContactsAndRepairsAtMostOnce() throws Exception {
        var gateway=mock(ModelGateway.class);when(gateway.modelName(ModelTier.FLASH)).thenReturn("fixture-model");
        when(gateway.stream(eq(ModelTier.FLASH),anyList(),eq(0.3))).thenReturn(response("bad"),response(REPORT));
        var optimizer=new ResumeOptimizer(gateway,json,new ByteArrayResource("遵守原文".getBytes(StandardCharsets.UTF_8)));
        var report=optimizer.optimize(source(),JD,ModelTier.FLASH).block(Duration.ofSeconds(3));
        assertThat(report.path("modelTier").asText()).isEqualTo("FLASH");assertThat(report.path("promptVersion").asText()).isEqualTo("resume-optimize-v1");
        verify(gateway,times(2)).stream(eq(ModelTier.FLASH),argThat(messages -> messages.toString().contains(JD)
            && !messages.toString().contains("private@example.test") && !messages.toString().contains("13800138000")),eq(0.3));
        reset(gateway);when(gateway.stream(any(),anyList(),eq(0.3))).thenReturn(response("bad"));
        assertThatThrownBy(() -> optimizer.optimize(source(),JD,ModelTier.FLASH).block(Duration.ofSeconds(3))).hasMessageContaining("未通过校验");
        verify(gateway,times(2)).stream(any(),anyList(),eq(0.3));
    }
    @Test void rejectsTrailingJsonOversizedStreamAndHidesProviderErrors() throws Exception {
        var gateway=mock(ModelGateway.class);var optimizer=new ResumeOptimizer(gateway,json,new ByteArrayResource("fixture".getBytes(StandardCharsets.UTF_8)));
        for(String invalid:List.of(REPORT+" {}","x".repeat(24001))) {
            reset(gateway);when(gateway.stream(any(),anyList(),eq(0.3))).thenReturn(response(invalid));
            assertThatThrownBy(() -> optimizer.optimize(source(),JD,ModelTier.FLASH).block(Duration.ofSeconds(3))).hasMessageContaining("未通过校验");
        }
        reset(gateway);when(gateway.stream(any(),anyList(),eq(0.3))).thenReturn(Flux.error(new IllegalStateException("private-provider-diagnostic")));
        assertThatThrownBy(() -> optimizer.optimize(source(),JD,ModelTier.FLASH).block(Duration.ofSeconds(3)))
            .isInstanceOf(BusinessException.class).hasMessageNotContaining("private-provider-diagnostic");
        verify(gateway).stream(any(),anyList(),eq(0.3));
    }
    @Test void filtersValidatedOutputAndReturnsMetadataWithoutRepairingTrivialChanges() throws Exception {
        var gateway=mock(ModelGateway.class);
        when(gateway.stream(any(),anyList(),eq(0.3))).thenReturn(response(REPORT.replace("参与后端服务开发", " 负责后端开发 ")));
        var optimizer=new ResumeOptimizer(gateway,json,new ByteArrayResource("fixture".getBytes(StandardCharsets.UTF_8)));
        var report=optimizer.optimize(source(),JD,ModelTier.FLASH).block(Duration.ofSeconds(3));
        assertThat(report.path("patches")).isEmpty();
        assertThat(report.path("filteredPatches").get(0).path("reason").asText()).isEqualTo("NORMALIZED_EQUAL");
        verify(gateway).stream(any(),anyList(),eq(0.3));

        reset(gateway);
        when(gateway.stream(any(),anyList(),eq(0.3))).thenReturn(response("{\"summary\":\"无需改写\",\"patches\":[]}"));
        var empty=optimizer.optimize(source(),JD,ModelTier.FLASH).block(Duration.ofSeconds(3));
        assertThat(empty.path("patches")).isEmpty();
        assertThat(empty.path("filteredPatches").isArray()).isTrue();
        assertThat(empty.path("filteredPatches")).isEmpty();
        verify(gateway).stream(any(),anyList(),eq(0.3));
    }
    @Test void validatesNumbersBeforeTinyEditFilterAndNeverHidesInvalidOutput() throws Exception {
        String from="参与后端服务开发，使用Java与Redis优化10个接口的缓存策略。";
        var source=source(); source.put("summary",from);
        ObjectNode invalid=(ObjectNode)json.readTree(REPORT);
        ((ObjectNode)invalid.path("patches").get(0)).put("from",from).put("value",from.replace("10", "11"));
        // A one-character invented number would be dropped lexically, but must fail validation first.
        assertThat(ResumeOptimizationValueFilter.filter(invalid).path("patches")).isEmpty();
        var gateway=mock(ModelGateway.class);
        when(gateway.stream(any(),anyList(),eq(0.3))).thenReturn(response(invalid.toString()));
        var optimizer=new ResumeOptimizer(gateway,json,new ByteArrayResource("fixture".getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> optimizer.optimize(source,JD,ModelTier.FLASH).block(Duration.ofSeconds(3)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.STRUCTURED_OUTPUT_INVALID));
        verify(gateway,times(2)).stream(any(),anyList(),eq(0.3));
    }
    Flux<ChatResponse> response(String text) {return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));}
}
