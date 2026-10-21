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
        return extractResult(document).structured();
    }
    public record Result(JsonNode structured, JsonNode metadata) {}
    private static final java.util.regex.Pattern NAME_WITH_ROLE = java.util.regex.Pattern.compile("^(.{2,}?)\\s*[（(]([^（）()]{2,12})[）)]$");
    private static final java.util.regex.Pattern ROLE_WORD = java.util.regex.Pattern.compile(
            "负责人|组长|队长|成员|主力|核心|开发|建模|设计|算法|测试|运营|产品|研究|分析|架构|前端|后端|全栈|实现|leader|developer|engineer|owner", java.util.regex.Pattern.CASE_INSENSITIVE);
    /**
     * RS-02: the common "项目名（角色）" heading is sometimes kept whole as the project name with an empty role.
     * Only an empty role is filled, and only when the bracket holds a role word, so "导航系统（微信小程序）" stays intact.
     */
    static JsonNode splitProjectRoles(JsonNode structured) {
        for (JsonNode project : structured.path("projects")) {
            if (!(project instanceof com.fasterxml.jackson.databind.node.ObjectNode node) || !node.path("role").asText().isBlank()) continue;
            var m = NAME_WITH_ROLE.matcher(node.path("name").asText().strip());
            if (m.matches() && ROLE_WORD.matcher(m.group(2)).find()) node.put("name", m.group(1).strip()).put("role", m.group(2).strip());
        }
        return structured;
    }
    public Result extractResult(ResumeDocumentReader.Document document) {
        return extractResult(document, null, null);
    }
    public Result extractResult(ResumeDocumentReader.Document document, String textTier, String visionTier) {
        long started = System.nanoTime();
        ModelTier tier = document.images().isEmpty()
                ? (textTier == null ? gateway.defaultTier() : ModelTier.from(textTier))
                : (visionTier == null ? ModelTier.VISION_ALT : ModelTier.from(visionTier));
        var usage = new java.util.concurrent.atomic.AtomicReference<org.springframework.ai.chat.metadata.Usage>();
        var media = document.images().stream().map(image -> new Media(MimeTypeUtils.parseMimeType(image.mime()),
            new ByteArrayResource(image.bytes()))).toList();
        var user = UserMessage.builder().text("<resume_data>\n" + document.text().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") + "\n</resume_data>").media(media).build();
        String output;
        try {
            output = gateway.stream(tier, List.of(new SystemMessage(prompt + "\n" + ResumeStructure.SCHEMA), user), 0.0)
                .doOnNext(response -> {
                    var tokens = response.getMetadata().getUsage();
                    if (tokens != null && tokens.getTotalTokens() != null && tokens.getTotalTokens() > 0) usage.set(tokens);
                })
                .map(response -> response.getResult() == null || response.getResult().getOutput().getText() == null
                    ? "" : response.getResult().getOutput().getText())
                .reduce(new StringBuilder(), (result, part) -> {
                    if (result.length() + part.length() > 100_000) throw new BusinessException(ErrorCode.STRUCTURED_OUTPUT_INVALID);
                    return result.append(part);
                }).map(StringBuilder::toString).block(Duration.ofSeconds(120));
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof java.util.concurrent.TimeoutException)
                    throw new BusinessException(ErrorCode.MODEL_TIMEOUT, "模型解析超时，请稍后重试或减少简历页数");
                if (cause instanceof org.springframework.web.reactive.function.client.WebClientResponseException http) {
                    if (http.getStatusCode().value() == 429) throw new BusinessException(ErrorCode.MODEL_RATE_LIMITED, "模型请求受限，请稍后重试并检查账户额度");
                    if (http.getStatusCode().value() == 401 || http.getStatusCode().value() == 403)
                        throw new BusinessException(ErrorCode.MODEL_NOT_CONFIGURED, "模型鉴权失败，请检查 API Key 与模型访问权限");
                    if (http.getStatusCode().value() == 503)
                        throw new BusinessException(ErrorCode.MODEL_UNAVAILABLE, "模型供应商暂不可用（503），请稍后重试");
                    if (http.getStatusCode().value() == 400 || http.getStatusCode().value() == 404)
                        throw new BusinessException(ErrorCode.MODEL_UNAVAILABLE, "模型拒绝请求，请检查模型名、图像支持和 API 地址");
                }
            }
            throw new BusinessException(ErrorCode.MODEL_UNAVAILABLE);
        }
        try {
            String normalized = output == null ? "" : output.strip();
            if (normalized.startsWith("```")) normalized = normalized.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
            var structured = splitProjectRoles(ResumeStructure.validate(json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(normalized)));
            var metadata = json.createObjectNode().put("channel", document.channel()).put("promptVersion", "v5")
                .put("model", gateway.modelName(tier)).put("tier", tier.name())
                .put("durationMs", (System.nanoTime() - started) / 1_000_000).put("imageCount", media.size());
            if (usage.get() != null) {
                metadata.put("inputTokens", usage.get().getPromptTokens());
                metadata.put("outputTokens", usage.get().getCompletionTokens());
                metadata.put("totalTokens", usage.get().getTotalTokens());
            }
            return new Result(structured, metadata);
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw new BusinessException(ErrorCode.STRUCTURED_OUTPUT_INVALID, "模型未返回有效的简历结构，请重试解析"); }
    }
}
