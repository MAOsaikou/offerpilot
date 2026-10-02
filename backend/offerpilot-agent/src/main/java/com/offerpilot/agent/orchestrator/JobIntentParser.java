package com.offerpilot.agent.orchestrator;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class JobIntentParser {
    record Intent(String action, String query, String site, String nature, String location, String question) {}
    private final ModelGateway gateway;
    private final ObjectMapper json;
    private final String prompt;

    public JobIntentParser(ModelGateway gateway, ObjectMapper json,
                           @Value("classpath:prompts/agent/job-intent.st") Resource prompt) throws IOException {
        this.gateway = gateway;
        this.json = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.prompt = prompt.getContentAsString(StandardCharsets.UTF_8);
    }

    public Mono<PlanStep> prepare(PlanStep step, String content, List<Message> history, ModelTier tier,
                                  Consumer<Usage> onUsage, Map<String, Object> context) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(prompt));
        messages.addAll(history.subList(Math.max(0, history.size() - 12), history.size()));
        messages.add(new UserMessage("本轮请求：" + content + "\n本步骤：" + step.title()));
        return attempt(messages, tier, onUsage).map(intent -> normalize(step, intent, context))
                .onErrorResume(IllegalArgumentException.class, ex ->
                        Mono.just(clarify(step, "请说明岗位关键词、城市和招聘类型，或从岗位详情选择简历版本后发起匹配。")));
    }

    private Mono<Intent> attempt(List<Message> messages, ModelTier tier, Consumer<Usage> onUsage) {
        return Mono.defer(() -> {
            AtomicReference<Usage> usage = new AtomicReference<>();
            return gateway.stream(tier, messages, 0.1).doOnNext(chunk -> {
                var current = chunk.getMetadata().getUsage();
                if (current != null && current.getTotalTokens() != null && current.getTotalTokens() > 0) usage.set(current);
            }).map(chunk -> chunk.getResult() == null || chunk.getResult().getOutput() == null
                    || chunk.getResult().getOutput().getText() == null ? "" : chunk.getResult().getOutput().getText())
                    .reduce(new StringBuilder(), (text, delta) -> {
                        if (text.length() + delta.length() > 4000) throw new IllegalArgumentException("参数过长");
                        return text.append(delta);
                    }).timeout(Duration.ofSeconds(60))
                    .doOnSuccess(ignored -> { if (usage.get() != null) onUsage.accept(usage.get()); })
                    .map(text -> {
                        try {
                            String value = text.toString().strip();
                            if (value.startsWith("```") && value.endsWith("```")) value = value.substring(value.indexOf('\n') + 1, value.length() - 3).strip();
                            var tree = json.readTree(value);
                            if (tree == null || !tree.isObject()) throw new IllegalArgumentException("参数必须为对象");
                            for (var fields = tree.fields(); fields.hasNext();) {
                                var field = fields.next();
                                if (!field.getValue().isTextual() && !field.getValue().isNull())
                                    throw new IllegalArgumentException("参数必须为字符串");
                            }
                            return json.treeToValue(tree, Intent.class);
                        } catch (IOException ex) { throw new IllegalArgumentException("参数无效", ex); }
                    });
        });
    }

    private PlanStep normalize(PlanStep step, Intent intent, Map<String, Object> context) {
        if (intent == null || intent.action() == null) throw new IllegalArgumentException("缺少动作");
        if ("clarify".equals(intent.action())) return clarify(step, field(intent.question(), 300, false));
        if ("optimize".equals(intent.action())) return ResumeIntentParser.optimize(step, context);
        if ("match".equals(intent.action())) {
            // 资源标识只来自调用方选定的上下文，绝不接受规划模型生成的 ID。
            for (String key : List.of("jobId", "resumeId", "resumeVersionId")) {
                if (!(context.get(key) instanceof String value) || value.isBlank() || value.length() > 80)
                    return clarify(step, "请先打开目标岗位详情，选择已保存的简历和版本，再点击在聊天中匹配。");
            }
            return new PlanStep(step.id(), "岗位与简历匹配", step.agent(), Map.of("action", "match"), step.dependsOn());
        }
        if (!"search".equals(intent.action())) throw new IllegalArgumentException("未知动作");
        String query = field(intent.query(), 100, false);
        String nature = field(intent.nature(), 10, false);
        if (!Set.of("campus", "intern", "social", "all").contains(nature)) throw new IllegalArgumentException("未知招聘类型");
        return new PlanStep(step.id(), "搜索真实岗位", step.agent(), Map.of("action", "search", "query", query,
                "site", field(intent.site(), 60, true), "nature", nature, "location", field(intent.location(), 60, true)), step.dependsOn());
    }

    private static String field(String value, int max, boolean optional) {
        if (value == null) value = "";
        value = value.strip();
        if (value.length() > max || (!optional && value.isEmpty())) throw new IllegalArgumentException("参数无效");
        return value;
    }

    private static PlanStep clarify(PlanStep step, String question) {
        return new PlanStep(step.id(), "确认岗位需求", step.agent(), Map.of("action", "clarify", "question", question), step.dependsOn());
    }
}
