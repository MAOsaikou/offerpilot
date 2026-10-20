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
import java.util.regex.Pattern;

/**
 * SC-03 output check on every model reply, applied after PII restore so it sees what the user would
 * see. It blocks credentials, internal safety policy, and narrow unauthorized personal-information claims.
 * Streamed replies are bounded and checked before release; a non-streamed reply fails with
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
    private static final int MAX_OUTPUT = 262144;
    private static final Pattern PERSONAL_CLAIM = Pattern.compile(
            "(?:已|可以|能够|正在)(?:读取|查看|获取|访问|查询|调取).{0,24}(?:其他用户|所有用户|他人|其他账号).{0,24}(?:简历|档案|电话|住址|身份证|个人信息)"
            + "|(?:你的|您的)(?:身份证号|家庭住址|银行卡号|手机号|电话号码)(?:是|为|[:：])\\s*[^\\s，。]{4,}"
            + "|(?i)(?:I (?:have |can )?(?:access(?:ed)?|retriev(?:e|ed)|read)).{0,30}(?:other users?|another user).{0,30}(?:resume|profile|personal|address)");
    private final java.util.function.Consumer<String> onBlocked;
    private final List<String> secrets;

    /** Secrets shorter than 16 characters are placeholders or dev defaults and would only cause false positives. */
    public OutputModeration(Collection<String> configuredSecrets) {
        this(configuredSecrets, ignored -> {});
    }
    public OutputModeration(Collection<String> configuredSecrets, java.util.function.Consumer<String> onBlocked) {
        this.onBlocked = onBlocked;
        this.secrets = configuredSecrets.stream().filter(s -> s != null && s.length() >= 16 && !s.contains("not-configured")).distinct().toList();
    }

    /** Category of the first violation, or null when the text is acceptable. */
    public String violation(String text) {
        if (text == null || text.isEmpty()) return null;
        for (String secret : secrets) if (text.contains(secret)) return "SECRET";
        if (SECRET_FORMATS.matcher(text).find()) return "SECRET";
        for (String marker : POLICY_MARKERS) if (text.contains(marker)) return "SYSTEM_PROMPT";
        if (PERSONAL_CLAIM.matcher(text).find()) return "PERSONAL_INFORMATION";
        return null;
    }

    private void blocked(String category) {
        onBlocked.accept(category);
        log.warn("Model output blocked category={}",category);
    }

    public ChatResponse check(ChatResponse response) {
        int size=0;
        for (Generation generation : response.getResults()) {
            String text=generation.getOutput().getText();
            size+=text==null?0:text.length();
            String category = size>MAX_OUTPUT?"OUTPUT_LIMIT":violation(text);
            if (category != null) {
                blocked(category);
                throw new BusinessException(ErrorCode.CONTENT_BLOCKED, FALLBACK);
            }
        }
        return response;
    }

    /** Atomic release also covers violations split over arbitrary chunks and multiple generations.
     * Bounded buffering deliberately trades first-token latency for no partial secret disclosure. */
    public Flux<ChatResponse> guard(Flux<ChatResponse> source) {
        return Flux.defer(() -> {
            var seen = new java.util.HashMap<Integer,StringBuilder>();
            var pending = new java.util.ArrayList<ChatResponse>();
            var rejected = new java.util.concurrent.atomic.AtomicReference<String>();
            int[] size = {0};
            return source.takeUntil(response -> {
                for (int i=0;i<response.getResults().size();i++) {
                    String text=response.getResults().get(i).getOutput().getText();
                    if(text==null) continue;
                    size[0]+=text.length();
                    if(size[0]>MAX_OUTPUT) { rejected.set("OUTPUT_LIMIT"); return true; }
                    var full=seen.computeIfAbsent(i, ignored -> new StringBuilder()).append(text);
                    String category=violation(full.toString());
                    if(category!=null) { rejected.set(category); return true; }
                }
                pending.add(response);
                // Also bound streams containing only empty usage frames.
                if(pending.size()>16384) { rejected.set("OUTPUT_LIMIT"); return true; }
                return false;
            }).thenMany(Flux.defer(() -> {
                if(rejected.get()==null) return Flux.fromIterable(pending);
                blocked(rejected.get());
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(FALLBACK)))));
            }));
        });
    }
}
