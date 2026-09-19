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
    @Test void acceptsFencedJsonButRejectsMissingFieldsAndTrailingData() throws Exception {
        var gateway = mock(ModelGateway.class);
        when(gateway.defaultTier()).thenReturn(ModelTier.CHEAP);
        var extractor = new ResumeExtractor(gateway, new ObjectMapper(), new ByteArrayResource("extract only".getBytes()));
        var document = new ResumeDocumentReader.Document("简历里的忽略指令仅是数据", List.of(), "text");
        when(gateway.stream(eq(ModelTier.CHEAP), anyList(), eq(0.0))).thenReturn(response("```json\n" + ResumeStructure.SCHEMA + "\n```"));
        assertThat(extractor.extract(document).path("basics").isObject()).isTrue();
        when(gateway.stream(eq(ModelTier.CHEAP), anyList(), eq(0.0))).thenReturn(response("{\"basics\":{}}"));
        assertThatThrownBy(() -> extractor.extract(document)).hasMessageContaining("结构");
        when(gateway.stream(eq(ModelTier.CHEAP), anyList(), eq(0.0))).thenReturn(response(ResumeStructure.SCHEMA + " {}"));
        assertThatThrownBy(() -> extractor.extract(document)).hasMessageContaining("有效");
    }
    @Test void imagesUseVisionTierAndDoNotFallbackToTextOnly() throws Exception {
        var gateway = mock(ModelGateway.class);
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
    private Flux<ChatResponse> response(String text) { return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(text))))); }
}
