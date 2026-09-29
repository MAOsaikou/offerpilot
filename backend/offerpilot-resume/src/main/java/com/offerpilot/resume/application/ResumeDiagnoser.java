package com.offerpilot.resume.application;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.common.error.*;
import com.offerpilot.llm.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ResumeDiagnoser {
    private final ModelGateway gateway;
    private final ObjectMapper json;
    private final String prompt;
    public ResumeDiagnoser(ModelGateway gateway, ObjectMapper json,
            @Value("classpath:prompts/resume/diagnose.st") Resource resource) throws java.io.IOException {
        this.gateway = gateway; this.json = json; this.prompt = resource.getContentAsString(StandardCharsets.UTF_8);
    }
    public Mono<ObjectNode> diagnose(JsonNode original, ModelTier tier) {
        return Mono.defer(() -> {
            gateway.requireAvailable(tier, false);
            ObjectNode source = original.deepCopy(); source.remove(List.of("basics", "confidence"));
            AtomicInteger input = new AtomicInteger(), output = new AtomicInteger();
            long started = System.nanoTime();
            return attempt(source, tier, false, input, output)
                .onErrorResume(BusinessException.class, ex -> ex.errorCode() == ErrorCode.STRUCTURED_OUTPUT_INVALID
                    ? attempt(source, tier, true, input, output) : Mono.error(ex))
                .timeout(Duration.ofSeconds(90))
                .map(report -> {
                    report.put("schemaVersion", 2).put("promptVersion", "resume-diagnosis-v1").put("ruleVersion", "campus-v1")
                        .put("model", gateway.modelName(tier)).put("modelTier", tier.name())
                        .put("durationMs", (System.nanoTime() - started) / 1_000_000)
                        .put("inputTokens", input.get()).put("outputTokens", output.get());
                    report.putArray("limitations").add("基于此保存版本的内容分析；未评估原文件排版，也不预测录用结果。")
                        .add("未选择目标岗位，本报告不评价岗位匹配度。建议需结合真实经历核对。");
                    var checks = report.putArray("checks");
                    boolean contact = !original.path("basics").path("phone").asText().isBlank()
                        || !original.path("basics").path("email").asText().isBlank();
                    if (!contact) checks.addObject().put("path", "/basics").put("message", "未填写电话或邮箱，请补充至少一种联系方式。");
                    if (original.path("education").isEmpty()) checks.addObject().put("path", "/education").put("message", "未填写教育经历，请核对解析结果并补充。");
                    int index = 0;
                    for (var finding : report.path("findings")) ((ObjectNode) finding).put("findingId", "f" + ++index);
                    return report;
                }).onErrorMap(error -> {
                    if (error instanceof BusinessException) return error;
                    if (error instanceof java.util.concurrent.TimeoutException) return new BusinessException(ErrorCode.MODEL_TIMEOUT, "简历诊断超时，请稍后重试");
                    return new BusinessException(ErrorCode.MODEL_UNAVAILABLE);
                });
        });
    }
    private Mono<ObjectNode> attempt(JsonNode source, ModelTier tier, boolean repair, AtomicInteger input, AtomicInteger output) {
        return Mono.defer(() -> {
            var messages = List.<Message>of(new SystemMessage(prompt + (repair ? "\n上次结构或引文校验失败。重新分析原文，严格遵守字段和逐字引用要求。" : "")),
                new UserMessage(source.toString()));
            AtomicInteger in = new AtomicInteger(), out = new AtomicInteger();
            return gateway.stream(tier, messages, 0.0).doOnNext(response -> {
                var usage = response.getMetadata().getUsage();
                if (usage != null) { if (usage.getPromptTokens() != null) in.set(usage.getPromptTokens()); if (usage.getCompletionTokens() != null) out.set(usage.getCompletionTokens()); }
            }).map(response -> response.getResult() == null || response.getResult().getOutput().getText() == null ? "" : response.getResult().getOutput().getText())
                .reduce(new StringBuilder(), (all, part) -> {
                    if (all.length() + part.length() > 24000) throw ResumeDiagnosisValidator.invalid();
                    return all.append(part);
                }).map(all -> {
                    try {
                        String text = all.toString().strip().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
                        var report = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
                        ResumeDiagnosisValidator.validate(report, source);
                        return (ObjectNode) report;
                    } catch (Exception ex) { throw ResumeDiagnosisValidator.invalid(); }
                }).doOnSuccess(value -> { input.addAndGet(in.get()); output.addAndGet(out.get()); })
                  .doOnError(error -> { input.addAndGet(in.get()); output.addAndGet(out.get()); });
        });
    }
}
