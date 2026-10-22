package com.offerpilot.interview.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import com.offerpilot.llm.quota.LlmUserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** IV-02: one follow-up per main question from the saved answer. The model asks; rules are the fallback. */
@Component
public class InterviewFollowUps {
    private static final Logger log = LoggerFactory.getLogger(InterviewFollowUps.class);
    private static final Pattern PREFIX = Pattern.compile("^(?:追问|面试官|问题|Q)\\s*[:：]\\s*|^[\"“「]+|[\"”」]+$");
    public record FollowUp(String question, String source) {}

    private final ObjectProvider<ModelGateway> gateways;
    private final ObjectMapper json;
    private final String prompt;

    public InterviewFollowUps(ObjectProvider<ModelGateway> gateways, ObjectMapper json,
            @Value("classpath:prompts/interview/follow-up.st") Resource resource) throws java.io.IOException {
        this.gateways = gateways;
        this.json = json;
        this.prompt = resource.getContentAsString(StandardCharsets.UTF_8);
    }

    public FollowUp generate(String owner, String role, String question, String answer) {
        var gateway = gateways.getIfAvailable();
        if (gateway != null) {
            try {
                gateway.requireAvailable(ModelTier.FLASH, false);
                String input = json.writeValueAsString(Map.of("role", role, "question", question,
                        "answer", com.offerpilot.common.text.TextBounds.truncate(answer, 3000),
                        "missingStar", InterviewRubric.detect(answer).missing()));
                List<Message> messages = List.of(new SystemMessage(prompt.replace("「目标岗位」", "「" + role + "」")),
                        new UserMessage(input));
                String text = LlmUserContext.asUser(owner, () -> gateway.stream(ModelTier.FLASH, messages, 0.4)
                        .map(r -> r.getResult() == null || r.getResult().getOutput().getText() == null ? "" : r.getResult().getOutput().getText())
                        .reduce("", String::concat).block(Duration.ofSeconds(25)));
                String cleaned = clean(text);
                if (cleaned != null) return new FollowUp(cleaned, "model");
                log.warn("面试追问模型输出不符合格式，改用规则追问");
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
                // Quota, missing key, timeout or provider error: the interview must continue offline.
                log.warn("面试追问模型不可用 ({})，改用规则追问", ex.getClass().getSimpleName());
            }
        }
        return rule(answer);
    }

    static String clean(String text) {
        if (text == null) return null;
        String line = text.strip().lines().map(String::strip).filter(s -> !s.isEmpty()).findFirst().orElse("");
        line = PREFIX.matcher(line).replaceAll("").strip();
        int length = line.codePointCount(0, line.length());
        if (length < 6 || length > 120 || !(line.endsWith("？") || line.endsWith("?"))) return null;
        return line;
    }

    /** Deterministic probe for the most important STAR gap, quoting the start of the answer. */
    static FollowUp rule(String answer) {
        String text = answer == null ? "" : answer.strip();
        var star = InterviewRubric.detect(text);
        String probe;
        if (!star.action()) probe = "具体是你本人做了哪几步？每一步为什么这样做？";
        else if (!star.result()) probe = "最后结果怎么样？你是用什么方式确认这个结果的？";
        else if (!star.task()) probe = "在这件事里你本人负责哪一部分，目标是什么？";
        else if (!star.situation()) probe = "当时是什么背景，有哪些时间、资源或技术上的限制？";
        else probe = "如果重新做一次，你会在哪一步做不同的选择？为什么？";
        String quote = text.split("[。！？!?；;\\n]")[0].strip();
        if (quote.codePointCount(0, quote.length()) > 20) quote = quote.substring(0, quote.offsetByCodePoints(0, 20)) + "…";
        return new FollowUp(quote.isEmpty() ? probe : "你提到「" + quote + "」。" + probe, "rule");
    }
}
