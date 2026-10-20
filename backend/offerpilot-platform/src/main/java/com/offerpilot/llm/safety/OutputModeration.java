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
 * Streamed replies retain a bounded suffix and check it before releasing each frame; a non-streamed reply fails with
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
    private static final String OTHER_USER_CLAIM = "(?:已|可以|能够|正在)(?:读取|查看|获取|访问|查询|调取).{0,24}(?:其他用户|所有用户|他人|其他账号).{0,24}(?:简历|档案|电话|住址|身份证|个人信息)";
    private static final String DIRECT_CLAIM = "(?:你的|您的)(?:身份证号|家庭住址|银行卡号|手机号|电话号码)(?:是|为|[:：])";
    private static final String ENGLISH_USER_CLAIM = "(?i)(?:I (?:have |can )?(?:access(?:ed)?|retriev(?:e|ed)|read)).{0,30}(?:other users?|another user).{0,30}(?:resume|profile|personal|address)";
    private static final Pattern PERSONAL_CLAIM = Pattern.compile(
            OTHER_USER_CLAIM + "|" + DIRECT_CLAIM + "\\s*[^\\s，。]{4,}|" + ENGLISH_USER_CLAIM);

    // A fixed suffix cannot protect the original JWT's unbounded middle segments or the direct
    // claim's unbounded whitespace. Streaming alone rejects an overlong candidate, even if it
    // would not ultimately match. Sync classification is unchanged. Terminal unbounded repeats
    // need only their minimum length as a witness (e.g. sk- + 20 chars).
    private static final int JWT_SEGMENT_CHARS = 64;
    private static final int CLAIM_WHITESPACE_CHARS = 32;
    private static final Pattern STREAM_SECRET_FORMATS = Pattern.compile(
            "sk-[A-Za-z0-9]{20}"
            + "|-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----"
            + "|eyJ[A-Za-z0-9_-]{10," + JWT_SEGMENT_CHARS + "}\\.eyJ[A-Za-z0-9_-]{10," + JWT_SEGMENT_CHARS + "}\\.[A-Za-z0-9_-]{10}"
            + "|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{36}"
            + "|eyJ[A-Za-z0-9_-]{" + (JWT_SEGMENT_CHARS + 1) + "}");
    private static final Pattern STREAM_PERSONAL_CLAIM = Pattern.compile(
            OTHER_USER_CLAIM
            + "|" + DIRECT_CLAIM + "\\s{0," + CLAIM_WHITESPACE_CHARS + "}[^\\s，。]{4}"
            + "|" + DIRECT_CLAIM + "\\s{" + (CLAIM_WHITESPACE_CHARS + 1) + "}"
            + "|" + ENGLISH_USER_CLAIM);
    // UTF-16 upper bounds on the shortest witness for each alternative; a potential prefix is
    // strictly shorter. Dot and negated classes can consume TWO UTF-16 chars per code point.
    // English is largest: "I have retrieved" + 30 dots + "another user" + 30 dots + "personal".
    private static final int REGEX_WINDOW_CHARS = Math.max(
            16 + 2 * 30 + 12 + 2 * 30 + 8,
            Math.max(2 * (3 + JWT_SEGMENT_CHARS) + 2 + 10,
                    Math.max(2 + 2 + 2 * 24 + 4 + 2 * 24 + 4,
                            2 + 4 + 1 + CLAIM_WHITESPACE_CHARS + 2 * 4)));
    private final java.util.function.Consumer<String> onBlocked;
    private final List<String> secrets;
    private final int retentionChars;

    /** Secrets shorter than 16 characters are placeholders or dev defaults and would only cause false positives. */
    public OutputModeration(Collection<String> configuredSecrets) {
        this(configuredSecrets, ignored -> {});
    }
    public OutputModeration(Collection<String> configuredSecrets, java.util.function.Consumer<String> onBlocked) {
        this.onBlocked = onBlocked;
        this.secrets = configuredSecrets.stream().filter(s -> s != null && s.length() >= 16 && !s.contains("not-configured")).distinct().toList();
        this.retentionChars = Math.max(REGEX_WINDOW_CHARS, Math.max(
                secrets.stream().mapToInt(String::length).max().orElse(0),
                POLICY_MARKERS.stream().mapToInt(String::length).max().orElse(0)));
        // Keep the documented proof/tests in sync when changing the finite streaming rules.
        assert REGEX_WINDOW_CHARS == 156 : "Update streaming regex bound and maximum-witness tests";
        assert 3 + JWT_SEGMENT_CHARS + 1 <= REGEX_WINDOW_CHARS;
        assert 2 + 4 + 1 + CLAIM_WHITESPACE_CHARS + 1 <= REGEX_WINDOW_CHARS;
        assert "-----BEGIN OPENSSH PRIVATE KEY-----".length() <= REGEX_WINDOW_CHARS;
        assert 4 + 36 <= REGEX_WINDOW_CHARS;
    }

    private String streamViolation(String text) {
        for (String secret : secrets) if (text.contains(secret)) return "SECRET";
        if (STREAM_SECRET_FORMATS.matcher(text).find()) return "SECRET";
        for (String marker : POLICY_MARKERS) if (text.contains(marker)) return "SYSTEM_PROMPT";
        return STREAM_PERSONAL_CLAIM.matcher(text).find() ? "PERSONAL_INFORMATION" : null;
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

    /** Keep only the undecided suffix of each generation; never flush it on error/cancellation. */
    public Flux<ChatResponse> guard(Flux<ChatResponse> source) {
        return Flux.defer(() -> {
            var tails = new java.util.HashMap<Integer, StringBuilder>();
            var pending = new java.util.ArrayList<ChatResponse>();
            var rejected = new java.util.concurrent.atomic.AtomicReference<String>();
            int[] size = {0};
            int[] frames = {0};
            return source.<ChatResponse>handle((response, sink) -> {
                for (int i=0;i<response.getResults().size();i++) {
                    String text=response.getResults().get(i).getOutput().getText();
                    if(text==null) continue;
                    size[0]+=text.length();
                    if(size[0]>MAX_OUTPUT) { rejected.set("OUTPUT_LIMIT"); break; }
                    var tail=tails.computeIfAbsent(i, ignored -> new StringBuilder()).append(text);
                    String category=streamViolation(tail.toString());
                    if(category!=null) { rejected.set(category); break; }
                }
                if (rejected.get() != null) { sink.complete(); return; }
                if (++frames[0] > 16384) { rejected.set("OUTPUT_LIMIT"); sink.complete(); return; }
                pending.add(response);
                sink.next(response);
            }).concatMapIterable(ignored -> release(pending, tails, false), 1)
              .concatWith(Flux.defer(() -> {
                if (rejected.get() == null) {
                    for (var tail : tails.values()) {
                        String category = streamViolation(tail.toString());
                        if (category != null) { rejected.set(category); break; }
                    }
                }
                if (rejected.get() == null) return Flux.fromIterable(release(pending, tails, true));
                blocked(rejected.get());
                pending.clear();
                tails.clear();
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(FALLBACK)))));
            }));
        });
    }

    private List<ChatResponse> release(List<ChatResponse> pending,
            java.util.Map<Integer, StringBuilder> tails, boolean finish) {
        var released = new java.util.ArrayList<ChatResponse>();
        var budget = new java.util.HashMap<Integer, Integer>();
        tails.forEach((i, tail) -> budget.put(i, Math.max(0, tail.length() - (finish ? 0 : retentionChars))));
        var iterator = pending.listIterator();
        boolean precedingComplete = true;
        while (iterator.hasNext()) {
            var frame = iterator.next();
            var prefix = new java.util.ArrayList<Generation>();
            var suffix = new java.util.ArrayList<Generation>();
            boolean hasText = false;
            // Keep cumulative usage metadata in provider order, including result-less usage frames.
            boolean complete = precedingComplete;
            for (int i = 0; i < frame.getResults().size(); i++) {
                var generation = frame.getResults().get(i);
                var output = generation.getOutput();
                String text = output.getText() == null ? "" : output.getText();
                int count = Math.min(text.length(), budget.getOrDefault(i, 0));
                // A zero-text frame's finish/tool metadata must follow preceding retained text.
                boolean done = count == text.length() && (finish || budget.getOrDefault(i, 0) > 0
                        || tails.getOrDefault(i, new StringBuilder()).isEmpty());
                complete &= done;
                hasText |= count > 0;
                budget.computeIfPresent(i, (key, remaining) -> remaining - count);
                if (count > 0) tails.get(i).delete(0, count);
                prefix.add(done ? generation : new Generation(new AssistantMessage(text.substring(0, count))));
                suffix.add(done ? new Generation(new AssistantMessage(""))
                        : new Generation(new AssistantMessage(text.substring(count), output.getMetadata(),
                            output.getToolCalls(), output.getMedia()), generation.getMetadata()));
            }
            if (complete) {
                released.add(frame);
                iterator.remove();
            } else if (hasText) {
                // Original metadata/tool calls travel only with the last fragment of the frame.
                released.add(new ChatResponse(prefix));
                iterator.set(new ChatResponse(suffix, frame.getMetadata()));
            }
            precedingComplete = complete;
        }
        return released;
    }
}
