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

    // One cancellable deadline includes generation and the optional evidence repair.
    // The injected scheduler lets tests advance time without sleeping or racing the host CPU.
    reactor.core.publisher.Mono<JsonNode> analyze(JsonNode job, JsonNode resume,
            com.offerpilot.llm.ModelTier tier, reactor.core.scheduler.Scheduler clock) {
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
            return generate(jd, resume, tier, messages, 0, start)
                    .timeout(budget, clock)
                    .onErrorMap(error -> modelFailure(error, tier, budget));
        });
    }

    private reactor.core.publisher.Mono<JsonNode> generate(String jd, JsonNode resume,
            com.offerpilot.llm.ModelTier tier, List<Message> messages, int attempt, long start) {
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
                        if (attempt == 1) return reactor.core.publisher.Mono.error(invalid());
                        messages.add(new SystemMessage("上次报告未通过结构或原文依据校验，未保存。请重新生成：至少一项、最多12项；jdQuote必须连续摘录且不重复；MET/PARTIAL的resumePath必须是存在的字符串叶子JSON Pointer，resumeQuote连续摘录该叶子；MISSING的两项简历证据必须为空。不要补造或拼接引文。仅输出完整JSON对象。"));
                        return generate(jd, resume, tier, messages, 1, start);
                    }
                    int points = 0;
                    for (var item : result.path("requirements")) points += switch (item.path("status").asText()) {
                        case "MET" -> 2; case "PARTIAL" -> 1; default -> 0;
                    };
                    result.put("score", Math.round(100f * points / (2 * result.path("requirements").size())));
                    result.put("model", gateway.modelName(tier)).put("modelTier", tier.name()).put("promptVersion", "job-match-v2");
                    result.put("durationMs", (System.nanoTime() - start) / 1000000);
                    return reactor.core.publisher.Mono.just((JsonNode) result);
                });
    }

    private static Throwable modelFailure(Throwable error, com.offerpilot.llm.ModelTier tier, Duration budget) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof BusinessException) return cause; // Includes LLM_RATE_LIMITED; never repair/retry IO failures.
            if (cause instanceof java.util.concurrent.TimeoutException || cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof java.net.SocketTimeoutException)
                return new BusinessException(ErrorCode.MODEL_TIMEOUT,
                        (tier == com.offerpilot.llm.ModelTier.PRO ? "深度" : "快速") + "匹配超过等待上限（" + budget.toSeconds()
                                + " 秒，含证据校验），未保存报告，请稍后重试或切换快速模式");
        }
        return new BusinessException(ErrorCode.MODEL_UNAVAILABLE);
    }

    /** Only verified, canonical source quotes enter the persisted report; isolated bad rows are discarded. */
    static void validate(JsonNode report,String jd,JsonNode resume) {
        if(report==null || !report.isObject() || !text(report,"summary",2000) || !report.path("requirements").isArray()
                || report.path("requirements").isEmpty() || report.path("requirements").size()>100) throw invalid();
        var verified=((ObjectNode)report).arrayNode();
        Set<String> quotes=new HashSet<>();
        for(var item:report.path("requirements")) {
            if(verified.size()==12) break;
            if(!text(item,"requirement",500) || !text(item,"jdQuote",1500) || !text(item,"reason",1500) || !text(item,"suggestion",1500)) continue;
            String quote=canonicalQuote(jd,item.path("jdQuote").asText());
            String status=item.path("status").asText();
            if(quote==null || quote.length()>1500 || quotes.contains(quote) || !Set.of("MET","PARTIAL","MISSING").contains(status)) continue;
            if(!item.path("resumePath").isTextual() || !item.path("resumeQuote").isTextual()) continue;
            String path=item.path("resumePath").asText(), evidence=item.path("resumeQuote").asText();
            if("MISSING".equals(status)) {
                if(!path.isEmpty() || !evidence.isEmpty()) continue;
            } else {
                if(!path.startsWith("/") || evidence.isBlank() || evidence.length()>1500) continue;
                JsonNode source;
                try { source=resume.at(path); } catch(Exception ex) { continue; }
                if(!source.isTextual()) continue;
                evidence=canonicalQuote(source.asText(),evidence);
                if(evidence==null || evidence.length()>1500) continue;
            }
            var row=((ObjectNode)report).objectNode();
            for(String key:List.of("requirement","status","reason","suggestion")) row.set(key,item.path(key));
            row.put("jdQuote",quote).put("resumePath",path).put("resumeQuote",evidence);
            verified.add(row); quotes.add(quote);
        }
        if(verified.isEmpty()) throw invalid();
        boolean filtered=verified.size()!=report.path("requirements").size();
        ((ObjectNode)report).retain("summary","requirements");
        ((ObjectNode)report).set("requirements",verified);
        if(filtered) ((ObjectNode)report).put("summary","已核实 " + verified.size() + " 项岗位原文与简历依据；未通过校验或重复的条目已移除，覆盖率仅按保留条目计算。未体现不等于不具备，请逐项核对准备建议。");
    }

    /** Formatting equivalence only: ignore whitespace and map full-width ASCII punctuation/letters.
     * Offsets retain the actual source substring. No fuzzy, case-insensitive or semantic matching. */
    static String canonicalQuote(String source,String quote) {
        if(quote.codePoints().noneMatch(cp->!Character.isWhitespace(cp) && !Character.isSpaceChar(cp))) return null;
        if(source.contains(quote)) return quote;
        var normalized=new StringBuilder();
        var starts=new ArrayList<Integer>(); var ends=new ArrayList<Integer>();
        for(int offset=0;offset<source.length();) {
            int cp=source.codePointAt(offset), end=offset+Character.charCount(cp);
            if(!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) {
                normalized.appendCodePoint(halfWidth(cp));
                for(int i=0;i<Character.charCount(halfWidth(cp));i++) { starts.add(offset); ends.add(end); }
            }
            offset=end;
        }
        var needle=new StringBuilder();
        quote.codePoints().filter(cp->!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)).forEach(cp->needle.appendCodePoint(halfWidth(cp)));
        if(needle.isEmpty()) return null;
        int at=normalized.indexOf(needle.toString());
        return at<0?null:source.substring(starts.get(at),ends.get(at+needle.length()-1));
    }
    private static int halfWidth(int cp) { return cp>=0xff01 && cp<=0xff5e?cp-0xfee0:cp; }
    private static boolean text(JsonNode node,String key,int max) { return node.path(key).isTextual() && !node.path(key).asText().isBlank() && node.path(key).asText().length()<=max; }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.STRUCTURED_OUTPUT_INVALID,"匹配报告的原文依据校验未通过，请重试；未保存未经核实的报告"); }
}
