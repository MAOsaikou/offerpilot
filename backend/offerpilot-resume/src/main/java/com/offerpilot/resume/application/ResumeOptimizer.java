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
public class ResumeOptimizer {
    private final ModelGateway gateway;
    private final ObjectMapper json;
    private final String prompt;
    public ResumeOptimizer(ModelGateway gateway, ObjectMapper json,
            @Value("classpath:prompts/resume/optimize.st") Resource resource) throws java.io.IOException {
        this.gateway = gateway; this.json = json; this.prompt = resource.getContentAsString(StandardCharsets.UTF_8);
    }
    public Mono<ObjectNode> optimize(JsonNode original, String jd, ModelTier tier) {
        return Mono.defer(() -> {
            gateway.requireAvailable(tier, false);
            ObjectNode source = original.deepCopy(); source.remove(List.of("basics", "confidence"));
            var input = json.createObjectNode().put("jobDescription", jd); input.set("resume", source);
            AtomicInteger in = new AtomicInteger(), out = new AtomicInteger(); long started = System.nanoTime();
            return attempt(original, input, jd, tier, false, in, out)
                .onErrorResume(BusinessException.class, ex -> ex.errorCode() == ErrorCode.STRUCTURED_OUTPUT_INVALID
                    ? attempt(original, input, jd, tier, true, in, out) : Mono.error(ex))
                .timeout(Duration.ofSeconds(90))
                .map(report -> report.put("promptVersion", "resume-optimize-v1").put("model", gateway.modelName(tier))
                    .put("modelTier", tier.name()).put("durationMs", (System.nanoTime() - started) / 1_000_000)
                    .put("inputTokens", in.get()).put("outputTokens", out.get()))
                .onErrorMap(error -> {
                    if (error instanceof BusinessException) return error;
                    if (error instanceof java.util.concurrent.TimeoutException) return new BusinessException(ErrorCode.MODEL_TIMEOUT, "简历改写超时，请稍后重试");
                    return new BusinessException(ErrorCode.MODEL_UNAVAILABLE);
                });
        });
    }
    private Mono<ObjectNode> attempt(JsonNode original, JsonNode input, String jd, ModelTier tier, boolean repair,
                                     AtomicInteger totalIn, AtomicInteger totalOut) {
        return Mono.defer(() -> {
            var messages = List.<Message>of(new SystemMessage(prompt + (repair
                ? "\n上次校验失败。重新核对路径、完整原文、JD逐字引文和数字；不得新增未知事实，允许空建议。" : "")), new UserMessage(input.toString()));
            AtomicInteger in = new AtomicInteger(), out = new AtomicInteger();
            return gateway.stream(tier, messages, 0.3).doOnNext(response -> {
                var usage = response.getMetadata().getUsage();
                if (usage != null) { if (usage.getPromptTokens() != null) in.set(usage.getPromptTokens()); if (usage.getCompletionTokens() != null) out.set(usage.getCompletionTokens()); }
            }).map(response -> response.getResult() == null || response.getResult().getOutput().getText() == null ? "" : response.getResult().getOutput().getText())
                .reduce(new StringBuilder(), (all, part) -> {
                    if (all.length() + part.length() > 24000) throw ResumeOptimizationValidator.invalid();
                    return all.append(part);
                }).map(all -> {
                    try {
                        String text = all.toString().strip().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
                        JsonNode report = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
                        ResumeOptimizationValidator.validate(report, original, jd);
                        return (ObjectNode) report;
                    } catch (Exception ex) { throw ResumeOptimizationValidator.invalid(); }
                }).doOnSuccess(value -> { totalIn.addAndGet(in.get()); totalOut.addAndGet(out.get()); })
                  .doOnError(error -> { totalIn.addAndGet(in.get()); totalOut.addAndGet(out.get()); });
        });
    }
}
