package com.offerpilot.llm.safety;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.llm.privacy.ModelInputBoundary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * SC-03 output check on every model reply, applied after PII restore so it sees what the user would
 * see. It blocks two things a reply must never contain: credentials (configured server keys and
 * common secret formats) and the fixed internal safety policy. A streamed reply stops at the
 * offending chunk and ends with a fixed fallback line; a non-streamed reply fails with
 * CONTENT_BLOCKED. Only the category is logged, never the text. This is a leak guard, not a
 * general-purpose content classifier.
 */
public final class OutputModeration {
    public static final String FALLBACK = "（后续内容未通过输出安全审核，已停止显示。我可以继续帮你处理简历、岗位匹配、面试准备等求职问题。）";
    private static final Logger log = LoggerFactory.getLogger(OutputModeration.class);
    private static final Pattern SECRET_FORMATS = Pattern.compile(
            "sk-[A-Za-z0-9]{20,}"
            + "|-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"
            + "|eyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"
            + "|AKIA[0-9A-Z]{16}"
            + "|gh[pousr]_[A-Za-z0-9]{36,}");
    // Distinctive sentences of the policy the gateway prepends; a reply quoting them is leaking it.
    private static final List<String> POLICY_MARKERS = List.of("OfferPilot 安全边界",
            ModelInputBoundary.POLICY.substring(ModelInputBoundary.POLICY.indexOf("用户消息"), ModelInputBoundary.POLICY.indexOf("用户消息") + 24));
    private static final int TAIL = 512;
    private final List<String> secrets;

    /** Secrets shorter than 16 characters are placeholders or dev defaults and would only cause false positives. */
    public OutputModeration(Collection<String> configuredSecrets) {
        this.secrets = configuredSecrets.stream().filter(s -> s != null && s.length() >= 16 && !s.contains("not-configured")).distinct().toList();
    }

    /** Category of the first violation, or null when the text is acceptable. */
    public String violation(String text) {
        if (text == null || text.isEmpty()) return null;
        for (String secret : secrets) if (text.contains(secret)) return "SECRET";
        if (SECRET_FORMATS.matcher(text).find()) return "SECRET";
        for (String marker : POLICY_MARKERS) if (text.contains(marker)) return "SYSTEM_PROMPT";
        return null;
    }

    public ChatResponse check(ChatResponse response) {
        for (Generation generation : response.getResults()) {
            String category = violation(generation.getOutput().getText());
            if (category != null) {
                log.warn("Model output blocked by moderation category={}", category);
                throw new BusinessException(ErrorCode.CONTENT_BLOCKED, "模型输出未通过安全审核，已拦截，请换个问法重试");
            }
        }
        return response;
    }

    public Flux<ChatResponse> guard(Flux<ChatResponse> source) {
        return Flux.defer(() -> {
            StringBuilder seen = new StringBuilder();
            AtomicBoolean blocked = new AtomicBoolean();
            return source.map(response -> {
                if (response.getResults().isEmpty()) return response;
                String text = response.getResult().getOutput().getText();
                if (text == null || text.isEmpty()) return response;
                seen.append(text);
                // A leak can straddle chunks, so check the new text together with what came just before it.
                String window = seen.substring(Math.max(0, seen.length() - text.length() - TAIL - longestSecret()));
                String category = violation(window);
                if (category == null) return response;
                blocked.set(true);
                log.warn("Streamed model output stopped by moderation category={}", category);
                return new ChatResponse(List.of(new Generation(new AssistantMessage(FALLBACK))), response.getMetadata());
            }).takeUntil(ignored -> blocked.get());
        });
    }

    private int longestSecret() { return secrets.stream().mapToInt(String::length).max().orElse(0); }
}
