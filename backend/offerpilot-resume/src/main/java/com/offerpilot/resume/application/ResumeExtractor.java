package com.offerpilot.resume.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class ResumeExtractor {
    private final ModelGateway gateway;
    private final ObjectMapper json;
    private final String prompt;
    public ResumeExtractor(ModelGateway gateway, ObjectMapper json,
            @Value("classpath:prompts/resume/extract.st") Resource prompt) throws java.io.IOException {
        this.gateway = gateway; this.json = json; this.prompt = prompt.getContentAsString(StandardCharsets.UTF_8);
    }
    public JsonNode extract(ResumeDocumentReader.Document document) {
        ModelTier tier = document.images().isEmpty() ? gateway.defaultTier() : ModelTier.VISION_ALT;
        var media = document.images().stream().map(image -> new Media(MimeTypeUtils.parseMimeType(image.mime()),
            new ByteArrayResource(image.bytes()))).toList();
        var user = UserMessage.builder().text("<resume_data>\n" + document.text() + "\n</resume_data>").media(media).build();
        String output = gateway.stream(tier, List.of(new SystemMessage(prompt + "\n" + ResumeStructure.SCHEMA), user), 0.0)
            .map(response -> response.getResult() == null || response.getResult().getOutput().getText() == null
                ? "" : response.getResult().getOutput().getText())
            .reduce(new StringBuilder(), (result, part) -> {
                if (result.length() + part.length() > 100_000) throw new BusinessException(ErrorCode.STRUCTURED_OUTPUT_INVALID);
                return result.append(part);
            }).map(StringBuilder::toString).block(Duration.ofSeconds(120));
        try {
            String normalized = output == null ? "" : output.strip();
            if (normalized.startsWith("```")) normalized = normalized.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
            return ResumeStructure.validate(json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(normalized));
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw new BusinessException(ErrorCode.STRUCTURED_OUTPUT_INVALID, "模型未返回有效的简历结构，请重试解析"); }
    }
}
