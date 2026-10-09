package com.offerpilot.resume.application;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

class ResumeDiagnosisTest {
    final ObjectMapper json=new ObjectMapper();
    static final String REPORT="""
        {"summary":"需要补充个人贡献", "strengths":[], "findings":[{"ruleId":"CONTRIBUTION","kind":"NEEDS_CLARIFICATION","priority":"MEDIUM","evidence":[{"path":"/summary","quote":"负责缓存优化"}],"reason":"缺少具体行动","suggestion":"补充实际策略和验证方式","questions":["采用了什么缓存策略？"]}]}
        """;
    ObjectNode source() throws Exception {
        ObjectNode source=(ObjectNode)json.readTree(ResumeStructure.SCHEMA);
        source.put("summary","负责缓存优化"); source.withObject("/basics").put("phone","13800138000").put("email","private@example.test");
        return source;
    }
    @Test void acceptsEvidenceAndEmptyFindingsWithoutInventingScores() throws Exception {
        ResumeDiagnosisValidator.validate(json.readTree(REPORT),source());
        ResumeDiagnosisValidator.validate(json.readTree("{\"summary\":\"暂无明确问题\",\"strengths\":[],\"findings\":[]}"),source());
    }
    @Test void rejectsWrongPathsInventedQuotesUnknownRulesAndUnansweredClarifications() throws Exception {
        for (String invalid : List.of(REPORT.replace("/summary","/projects/9/bullets/0"),REPORT.replace("负责缓存优化","性能提升60%"),
                REPORT.replace("CONTRIBUTION","SCHOOL_PRESTIGE"),REPORT.replace("[\"采用了什么缓存策略？\"]","[]"),
                REPORT.replace("/summary","/basics/phone").replace("负责缓存优化","13800138000"))) {
            assertThatThrownBy(() -> ResumeDiagnosisValidator.validate(json.readTree(invalid),source())).hasMessageContaining("校验");
        }
        var report=(ObjectNode)json.readTree(REPORT); report.put("score",99);
        assertThatThrownBy(() -> ResumeDiagnosisValidator.validate(report,source())).hasMessageContaining("校验");
    }
    @Test void usesSelectedModelRedactsContactsAndLimitsRepairToOneAttempt() throws Exception {
        var gateway=mock(ModelGateway.class);
        when(gateway.modelName(ModelTier.FLASH)).thenReturn("fixture-model");
        when(gateway.stream(eq(ModelTier.FLASH),anyList(),eq(0.0))).thenReturn(response("bad"),response(REPORT));
        var diagnoser=new ResumeDiagnoser(gateway,json,new ByteArrayResource("diagnose".getBytes(StandardCharsets.UTF_8)));
        var report=diagnoser.diagnose(source(),ModelTier.FLASH).block(Duration.ofSeconds(3));
        assertThat(report.path("schemaVersion").asInt()).isEqualTo(2);
        assertThat(report.has("overall")).isFalse();
        assertThat(report.path("findings").get(0).path("findingId").asText()).isEqualTo("f1");
        verify(gateway,times(2)).stream(eq(ModelTier.FLASH),argThat(messages -> !messages.toString().contains("13800138000") && !messages.toString().contains("private@example.test")),eq(0.0));
        reset(gateway);
        when(gateway.stream(any(),anyList(),eq(0.0))).thenReturn(response("bad"));
        assertThatThrownBy(() -> diagnoser.diagnose(source(),ModelTier.FLASH).block(Duration.ofSeconds(3))).hasMessageContaining("校验");
        verify(gateway,times(2)).stream(any(),anyList(),eq(0.0));
    }
    @Test void providerErrorsAreSafeAndNotRetriedAsSchemaErrors() throws Exception {
        var gateway=mock(ModelGateway.class);
        when(gateway.stream(any(),anyList(),eq(0.0))).thenReturn(Flux.error(new RuntimeException("private-key-and-provider-body")));
        var diagnoser=new ResumeDiagnoser(gateway,json,new ByteArrayResource("diagnose".getBytes()));
        assertThatThrownBy(() -> diagnoser.diagnose(source(),ModelTier.FLASH).block(Duration.ofSeconds(3))).hasMessageNotContaining("private-key").hasMessageContaining("模型调用失败");
        verify(gateway).stream(any(),anyList(),eq(0.0));
    }
    private Flux<ChatResponse> response(String text) { return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(text))))); }
}
