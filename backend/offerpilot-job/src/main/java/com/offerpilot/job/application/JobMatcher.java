package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.common.error.*;
import com.offerpilot.llm.ModelGateway;
import org.springframework.ai.chat.messages.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Component
public class JobMatcher {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JobMatcher.class);
    private final ModelGateway gateway;
    private final ObjectMapper json;
    private final String prompt;
    public JobMatcher(ModelGateway gateway,ObjectMapper json,@Value("classpath:prompts/job/match.st") Resource prompt) throws java.io.IOException {
        this.gateway=gateway; this.json=json; this.prompt=prompt.getContentAsString(StandardCharsets.UTF_8);
    }
    public JsonNode match(JsonNode job, JsonNode resume) {
        return match(job, resume, gateway.defaultTier());
    }

    public JsonNode match(JsonNode job, JsonNode resume, com.offerpilot.llm.ModelTier tier) {
        return analyze(job, resume, tier, reactor.core.scheduler.Schedulers.parallel()).block();
    }

    public JsonNode match(JsonNode job, JsonNode resume, com.offerpilot.llm.ModelTier tier,
                          org.reactivestreams.Publisher<?> cancellation) {
        return analyze(job, resume, tier == null ? gateway.defaultTier() : tier,
                reactor.core.scheduler.Schedulers.parallel()).takeUntilOther(cancellation)
                .switchIfEmpty(reactor.core.publisher.Mono.error(new BusinessException(ErrorCode.GENERATION_CANCELLED)))
                .block();
    }

    // One cancellable deadline includes generation and the optional evidence repair.
    // The injected scheduler lets tests advance time without sleeping or racing the host CPU.
    reactor.core.publisher.Mono<JsonNode> analyze(JsonNode job, JsonNode resume,
            com.offerpilot.llm.ModelTier tier, reactor.core.scheduler.Scheduler clock) {
        String owner = com.offerpilot.llm.quota.LlmUserContext.current();
        return reactor.core.publisher.Mono.defer(() -> {
            String jd = JobService.evidenceText(job);
            if (job.path("description").asText().isBlank() && job.path("requirement").asText().isBlank())
                throw new BusinessException(ErrorCode.JOB_CONTENT_MISSING);
            var input = json.createObjectNode().put("jobText", jd); input.set("resume", resume);
            if (input.toString().length() > 100000)
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "简历或岗位内容过长，请使用精简后的简历版本");
            long start = System.nanoTime();
            List<Message> messages = new ArrayList<>(List.of(new SystemMessage(prompt), new UserMessage(input.toString())));
            Duration budget = gateway.timeout(tier);
            var best = new java.util.concurrent.atomic.AtomicReference<ObjectNode>();
            return generate(jd, resume, tier, messages, 0, start, null, best)
                    .timeout(budget, clock)
                    .onErrorResume(java.util.concurrent.TimeoutException.class, error -> best.get() == null
                            ? reactor.core.publisher.Mono.error(error) : reactor.core.publisher.Mono.just(best.get()))
                    .onErrorMap(error -> modelFailure(error, tier, budget));
        }).contextWrite(context -> owner == null ? context
                : com.offerpilot.llm.quota.LlmUserContext.withUser(context, owner));
    }

    private reactor.core.publisher.Mono<JsonNode> generate(String jd, JsonNode resume,
            com.offerpilot.llm.ModelTier tier, List<Message> messages, int attempt, long start,
            ObjectNode fallback, java.util.concurrent.atomic.AtomicReference<ObjectNode> best) {
        return reactor.core.publisher.Mono.defer(() -> gateway.stream(tier, messages, 0)
                .map(r -> r.getResult() == null || r.getResult().getOutput().getText() == null
                        ? "" : r.getResult().getOutput().getText())
                .reduce(new StringBuilder(), (all, part) -> {
                    if (all.length() + part.length() > 40000) throw invalid();
                    return all.append(part);
                }).map(StringBuilder::toString))
                .flatMap(output -> {
                    ObjectNode result;
                    try {
                        String clean = output.strip().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
                        JsonNode report = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(clean);
                        validate(report, jd, resume);
                        result = (ObjectNode) report;
                    } catch (Exception ex) {
                        if (attempt == 1) return fallback == null ? reactor.core.publisher.Mono.error(invalid()) : reactor.core.publisher.Mono.just(fallback);
                        messages.add(new SystemMessage("上次报告未通过结构或原文依据校验，未保存。请重新生成：至少一项，尽量不超过12项；枚举中的每个具体技能都必须可评估，不同要求可共享同一段连续jdQuote；合并要求必须列全技能并按全部技能判断状态；MET/PARTIAL的resumePath必须是存在的字符串叶子JSON Pointer，resumeQuote连续摘录该叶子；MISSING的两项简历证据必须为空。不要补造或拼接引文。仅输出完整JSON对象。"));
                        return generate(jd, resume, tier, messages, 1, start, null, best);
                    }
                    finish(result, tier, start);
                    if (best.get() == null || result.path("verifiedCount").asInt() > best.get().path("verifiedCount").asInt()) best.set(result);
                    int total = result.path("verifiedCount").asInt() + result.path("unverifiedCount").asInt();
                    if (attempt == 0 && result.path("verifiedCount").asInt() * 2 < total) {
                        messages.add(new SystemMessage("上次原文依据校验只核实了少量条目，未保存。请按原文修复路径与连续引文，保留所有要求；不添加或拼接证据。"));
                        return generate(jd, resume, tier, messages, 1, start, result, best)
                                .map(repaired -> repaired.path("verifiedCount").asInt() >= result.path("verifiedCount").asInt() ? repaired : result)
                                .onErrorReturn(result);
                    }
                    return reactor.core.publisher.Mono.just((JsonNode) result);
                });
    }

    private JsonNode finish(ObjectNode result, com.offerpilot.llm.ModelTier tier, long start) {
        int points = 0;
        for (var item : result.path("requirements")) points += switch (item.path("status").asText()) {
            case "MET" -> 2; case "PARTIAL" -> 1; default -> 0;
        };
        result.put("score", Math.round(100f * points / (2 * result.path("requirements").size())));
        result.put("model", gateway.modelName(tier)).put("modelTier", tier.name()).put("promptVersion", "job-match-v3");
        result.put("durationMs", (System.nanoTime() - start) / 1000000);
        return result;
    }

    private static Throwable modelFailure(Throwable error, com.offerpilot.llm.ModelTier tier, Duration budget) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof BusinessException) return cause; // Includes LLM_RATE_LIMITED; never repair/retry IO failures.
            if (cause instanceof org.springframework.web.reactive.function.client.WebClientResponseException response
                    && response.getStatusCode().value() == 504)
                return new BusinessException(ErrorCode.MODEL_TIMEOUT,
                        "模型服务返回等待超时，未保存匹配报告，请稍后重试或切换快速模式");
            if (cause instanceof java.net.http.HttpConnectTimeoutException)
                return new BusinessException(ErrorCode.MODEL_TIMEOUT,
                        "模型连接超时（连接上限10秒），未保存匹配报告，请稍后重试");
            if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException)
                return new BusinessException(ErrorCode.MODEL_TIMEOUT,
                        "模型响应读取超时，未保存匹配报告，请稍后重试或切换快速模式");
            if (cause instanceof java.util.concurrent.TimeoutException)
                return new BusinessException(ErrorCode.MODEL_TIMEOUT,
                        (tier == com.offerpilot.llm.ModelTier.PRO ? "深度" : "快速") + "匹配超过等待上限（" + budget.toSeconds()
                                + " 秒，含证据校验），未保存报告，请稍后重试或切换快速模式");
        }
        return new BusinessException(ErrorCode.MODEL_UNAVAILABLE);
    }

    /** Keep verified evidence and report omitted items separately; never persist failed quotes. */
    static void validate(JsonNode report, String jd, JsonNode resume) {
        if (report == null || !report.isObject() || !text(report, "summary", 2000)
                || !report.path("requirements").isArray() || report.path("requirements").isEmpty()
                || report.path("requirements").size() > 100) throw invalid();
        var verified = ((ObjectNode) report).arrayNode();
        var omitted = ((ObjectNode) report).arrayNode();
        Set<String> requirements = new HashSet<>();
        int index = 0;
        for (var item : report.path("requirements")) {
            String identity = requirementKey(item.path("requirement").asText());
            String issue = null;
            String quote = null, evidence = "", path = "";
            if (!text(item, "requirement", 500) || !text(item, "jdQuote", 1500)
                    || !text(item, "reason", 1500) || !text(item, "suggestion", 1500)) issue = "INVALID_FIELDS";
            else if (requirements.contains(identity)) issue = "DUPLICATE";
            else if ((quote = canonicalQuote(jd, item.path("jdQuote").asText())) == null || quote.length() > 1500) issue = "JD_QUOTE";
            else if (!Set.of("MET", "PARTIAL", "MISSING").contains(item.path("status").asText())) issue = "STATUS";
            else if (!item.path("resumePath").isTextual() || !item.path("resumeQuote").isTextual()) issue = "RESUME_FIELDS";
            else {
                path = item.path("resumePath").asText();
                evidence = item.path("resumeQuote").asText();
                if ("MISSING".equals(item.path("status").asText())) {
                    if (!path.isEmpty() || !evidence.isEmpty()) issue = "MISSING_WITH_EVIDENCE";
                } else {
                    JsonNode source = null;
                    if (path.startsWith("/")) {
                        try { source = resume.at(path); } catch (IllegalArgumentException ignored) { }
                    }
                    if (source == null || !source.isTextual()) issue = "RESUME_PATH";
                    else if (evidence.isBlank() || evidence.length() > 1500
                            || (evidence = canonicalQuote(source.asText(), evidence)) == null || evidence.length() > 1500) issue = "RESUME_QUOTE";
                }
            }
            if (issue != null) {
                // Index + field + quote fingerprint identifies the rejected quote without logging resume PII.
                String field = issue.equals("JD_QUOTE") ? "jdQuote" : "resumeQuote";
                String raw = item.path(field).asText();
                log.warn("Match verification rejected item={} requirement={} issue={} quoteField={} quoteHash={} quoteLength={}",
                        index++, safeLabel(item.path("requirement").asText()), issue, field,
                        Integer.toHexString(raw.hashCode()), raw.length());
                omitted.addObject().put("requirement", safeLabel(item.path("requirement").asText()))
                        .put("status", "UNVERIFIED").put("verificationIssue", issue);
                continue;
            }
            var row = ((ObjectNode) report).objectNode();
            for (String key : List.of("requirement", "status", "reason", "suggestion")) row.set(key, item.path(key));
            row.put("jdQuote", quote).put("resumePath", path).put("resumeQuote", evidence);
            verified.add(row); requirements.add(identity); index++;
        }
        log.debug("Match verification generated={} verified={} unverified={}", report.path("requirements").size(), verified.size(), omitted.size());
        if (verified.isEmpty()) throw invalid();
        ((ObjectNode) report).retain("summary", "requirements");
        ((ObjectNode) report).set("requirements", verified);
        ((ObjectNode) report).set("unverifiedRequirements", omitted);
        ((ObjectNode) report).put("verifiedCount", verified.size()).put("unverifiedCount", omitted.size());
        if (!omitted.isEmpty()) ((ObjectNode) report).put("summary", "已核实 " + verified.size()
                + " 项，另有 " + omitted.size() + " 项未核实（不计分）；覆盖率仅按已核实项计算。未体现不等于不具备，请逐项核对准备建议。");
    }

    static String requirementKey(String text) {
        return java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
                .replaceAll("[\\s\\p{Z}]+", "").toLowerCase(Locale.ROOT);
    }

    private static String safeLabel(String label) {
        return com.offerpilot.common.text.TextBounds.truncate(label.replaceAll("[\\r\\n\\t]", " "), 160);
    }

    /** Formatting equivalence only: ignore whitespace and map full-width ASCII punctuation/letters.
     * Offsets retain the actual source substring. No fuzzy, case-insensitive or semantic matching. */
    static String canonicalQuote(String source,String quote) {
        if(quote.codePoints().noneMatch(cp->!Character.isWhitespace(cp) && !Character.isSpaceChar(cp))) return null;
        if(source.contains(quote)) return quote;
        var normalized=new StringBuilder();
        var prefixes = enumerationPrefixes(source);
        var starts=new ArrayList<Integer>(); var ends=new ArrayList<Integer>();
        for(int offset=0;offset<source.length();) {
            int cp=source.codePointAt(offset), end=offset+Character.charCount(cp);
            if(!prefixes.get(offset) && !Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) {
                normalized.appendCodePoint(halfWidth(cp));
                for(int i=0;i<Character.charCount(halfWidth(cp));i++) { starts.add(offset); ends.add(end); }
            }
            offset=end;
        }
        var needle=new StringBuilder();
        var quotePrefixes = enumerationPrefixes(quote);
        for (int offset = 0; offset < quote.length();) {
            int cp = quote.codePointAt(offset);
            if (!quotePrefixes.get(offset) && !Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) needle.appendCodePoint(halfWidth(cp));
            offset += Character.charCount(cp);
        }
        if(needle.isEmpty()) return null;
        int at=normalized.indexOf(needle.toString());
        if (at >= 0) return source.substring(starts.get(at), ends.get(at + needle.length() - 1));
        // DeepSeek sometimes adds a clause-ending semicolon/full stop. Strip only terminal marks,
        // never interior punctuation, ellipses or words (so stitched enumerations still fail).
        String trimmed = needle.toString().replaceFirst("[;。.]+$", "");
        if (trimmed.isEmpty() || trimmed.equals(needle.toString())) return null;
        at = normalized.indexOf(trimmed);
        return at < 0 ? null : source.substring(starts.get(at), ends.get(at + trimmed.length() - 1));
    }
    private static BitSet enumerationPrefixes(String text) {
        var skipped = new BitSet(text.length());
        var matcher = java.util.regex.Pattern.compile("(?m)^[\\h]*[0-9０-９]{1,3}[.．、)）](?![0-9０-９])[\\h]*").matcher(text);
        while (matcher.find()) skipped.set(matcher.start(), matcher.end());
        return skipped;
    }
    private static int halfWidth(int cp) { return switch (cp) {
        case 0x2018, 0x2019 -> '\'';
        case 0x201c, 0x201d -> '"';
        default -> cp >= 0xff01 && cp <= 0xff5e ? cp - 0xfee0 : cp;
    }; }
    private static boolean text(JsonNode node,String key,int max) { return node.path(key).isTextual() && !node.path(key).asText().isBlank() && node.path(key).asText().length()<=max; }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.STRUCTURED_OUTPUT_INVALID,"匹配报告没有可核实的条目，未保存报告；请重试", true); }
}
