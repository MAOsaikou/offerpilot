package com.offerpilot.agent.orchestrator;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.domain.ExpertType;
import com.offerpilot.agent.domain.PlanStep;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Component
public class ResumeIntentParser {
    record Intent(String action, String question) {}
    private final ModelGateway gateway;
    private final ObjectMapper json;
    private final String prompt;
    public ResumeIntentParser(ModelGateway gateway, ObjectMapper json,
            @Value("classpath:prompts/agent/resume-intent.st") Resource prompt) throws IOException {
        this.gateway = gateway;
        this.json = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.prompt = prompt.getContentAsString(StandardCharsets.UTF_8);
    }
    public Mono<PlanStep> prepare(PlanStep step, String content, List<Message> history, ModelTier tier,
                                 Consumer<Usage> onUsage, Map<String, Object> context) {
        if (context != null && "applyPatches".equals(context.get("resumeAction"))) {
            var args = new LinkedHashMap<String, Object>();
            for (String key : List.of("resumeId", "resumeVersionId", "batchId", "accepted", "edited", "confirmed"))
                if (context.containsKey(key)) args.put(key, context.get(key));
            try {
                com.offerpilot.agent.application.ResumePatchWriteTools.validate(args);
                args.put("action", "applyPatches");
                return Mono.just(new PlanStep(step.id(), "采纳所选简历改写", ExpertType.RESUME, Map.copyOf(args), step.dependsOn()));
            } catch (com.offerpilot.common.error.BusinessException ex) {
                return Mono.just(clarify(step, "请在改写卡片上选择补丁、核对编辑与确认项后重新采纳；需关联简历、版本和批次。"));
            }
        }
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(prompt));
        messages.addAll(history.subList(Math.max(0, history.size() - 12), history.size()));
        messages.add(new UserMessage("本轮请求：" + content + "\n本步骤：" + step.title()
                + "\n已关联改写建议：" + selected(context, "optimizationReportId")
                + "\n已关联诊断报告：" + selected(context, "diagnosisReportId")));
        return Mono.defer(() -> {
            AtomicReference<Usage> usage = new AtomicReference<>();
            return com.offerpilot.llm.ChatLatency.flux("resume-action-model", gateway.stream(tier, messages, 0.1)).doOnNext(chunk -> {
                var current = chunk.getMetadata().getUsage();
                if (current != null && current.getTotalTokens() != null && current.getTotalTokens() > 0) usage.set(current);
            }).map(chunk -> chunk.getResult() == null || chunk.getResult().getOutput() == null
                    || chunk.getResult().getOutput().getText() == null ? "" : chunk.getResult().getOutput().getText())
              .reduce(new StringBuilder(), (text, delta) -> {
                  if (text.length() + delta.length() > 2000) throw new IllegalArgumentException("动作输出过长");
                  return text.append(delta);
              }).timeout(Duration.ofSeconds(60))
              .doOnSuccess(ignored -> { if (usage.get() != null) onUsage.accept(usage.get()); })
              .map(text -> {
                  try {
                      String value = text.toString().strip();
                      if (value.startsWith("```") && value.endsWith("```")) value = value.substring(value.indexOf('\n') + 1, value.length() - 3).strip();
                      var node = json.readTree(value);
                      if (node == null || !node.isObject() || !node.path("action").isTextual()
                              || node.has("question") && !node.path("question").isTextual() && !node.path("question").isNull())
                          throw new IllegalArgumentException("无效动作");
                      return normalize(step, json.treeToValue(node, Intent.class), context);
                  } catch (IOException ex) { throw new IllegalArgumentException("无效动作", ex); }
              });
        }).onErrorResume(IllegalArgumentException.class, ex -> Mono.just(clarify(step,
                "请说明要诊断、按岗位生成改写，还是讨论已有建议；从岗位或简历页选择保存版本后继续。")));
    }
    private PlanStep normalize(PlanStep step, Intent intent, Map<String,Object> context) {
        // Bound reports are authoritative even when the model calls a vague follow-up advice/clarify.
        // Only an explicit optimization request may generate new suggestions instead of discussing them.
        if (hasVersion(context) && Set.of("diagnose", "discuss", "advice", "clarify").contains(intent.action())) {
            if (selected(context, "diagnosisReportId")) return action(step, "读取已有诊断报告", "diagnose");
            if (selected(context, "optimizationReportId") && !"diagnose".equals(intent.action()))
                return action(step, "读取已有改写建议", "read_optimization");
        }
        return switch (intent.action()) {
            case "optimize" -> optimize(step, context);
            case "diagnose" -> hasVersion(context) ? action(step, "诊断已保存简历", "diagnose")
                    : clarify(step, "请先从简历详情选择已保存的简历版本。" );
            case "discuss" -> !hasVersion(context) ? clarify(step, "请先从对应的简历报告打开讨论入口。")
                    : selected(context, "optimizationReportId") ? action(step, "读取已有改写建议", "read_optimization")
                    : selected(context, "diagnosisReportId") ? action(step, "读取已有诊断报告", "diagnose")
                    : clarify(step, "请先从已有诊断报告或改写建议打开讨论入口。" );
            case "advice" -> action(step, "简历文字指导", "advice");
            case "clarify" -> {
                if (intent.question() == null || intent.question().isBlank() || intent.question().length() > 300)
                    throw new IllegalArgumentException("缺少问题");
                yield clarify(step, intent.question());
            }
            default -> throw new IllegalArgumentException("未知动作");
        };
    }
    static PlanStep optimize(PlanStep step, Map<String,Object> context) {
        return hasVersion(context) && selected(context, "jobId") ? action(step, "生成岗位定向改写建议", "optimize")
                : clarify(step, "请先打开目标岗位详情，选择已保存的简历及版本，再发起聊天改写；也可在简历改写页粘贴JD。" );
    }
    private static boolean hasVersion(Map<String,Object> context) {
        return selected(context, "resumeId") && selected(context, "resumeVersionId");
    }
    private static boolean selected(Map<String,Object> context, String key) {
        return context != null && context.get(key) instanceof String value && !value.isBlank() && value.length() <= 80;
    }
    private static PlanStep action(PlanStep step, String title, String action) {
        return new PlanStep(step.id(), title, ExpertType.RESUME, Map.of("action", action), step.dependsOn());
    }
    private static PlanStep clarify(PlanStep step, String question) {
        return new PlanStep(step.id(), "确认简历需求", ExpertType.RESUME, Map.of("action", "clarify", "question", question), step.dependsOn());
    }
}
