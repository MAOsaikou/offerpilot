package com.offerpilot.career.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CD-02: infer six RIASEC strengths and 5-8 traits from assessment answers. Every evidence quote must
 * be verbatim from the user's answers; unsupported model claims are dropped, and keyword rules are the
 * offline fallback so the flow never depends on a provider.
 */
@Component
public class CareerAssessmentInference {
    private static final Logger log = LoggerFactory.getLogger(CareerAssessmentInference.class);
    public static final String LETTERS = "RIASEC";

    public record Trait(String name, double confidence, String evidence) {}
    public record Constraints(String city, String salary, String overtime) {
        public static final Constraints EMPTY = new Constraints("", "", "");
    }
    public record Inference(Map<String, Integer> riasec, Map<String, String> riasecEvidence, List<Trait> traits,
                            Constraints constraints, String summary, String source) {}
    public record QA(String question, String answer) {}

    private final ObjectProvider<ModelGateway> gateways;
    private final ObjectMapper json;
    private final String prompt;

    public CareerAssessmentInference(ObjectProvider<ModelGateway> gateways, ObjectMapper json,
            @Value("classpath:prompts/career/assessment-infer.st") Resource resource) throws java.io.IOException {
        this.gateways = gateways;
        this.json = json;
        this.prompt = resource.getContentAsString(StandardCharsets.UTF_8);
    }

    public Inference infer(String owner, List<QA> answers) {
        var gateway = gateways.getIfAvailable();
        if (gateway != null) {
            try {
                gateway.requireAvailable(ModelTier.FLASH, false);
                List<Message> messages = List.of(new SystemMessage(prompt), new UserMessage(json.writeValueAsString(answers)));
                String text = LlmUserContext.asUser(owner, () -> gateway.stream(ModelTier.FLASH, messages, 0.2)
                        .map(r -> r.getResult() == null || r.getResult().getOutput().getText() == null ? "" : r.getResult().getOutput().getText())
                        .reduce("", String::concat).block(Duration.ofSeconds(60)));
                var parsed = validate(text, answers);
                if (parsed != null) return parsed;
                log.warn("测评画像模型输出未通过校验，改用规则推断");
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
                log.warn("测评画像模型不可用 ({})，改用规则推断", ex.getClass().getSimpleName());
            }
        }
        return rules(answers);
    }

    /** Parse and keep only claims grounded in the user's own words. Null when unusable. */
    Inference validate(String text, List<QA> answers) {
        if (text == null) return null;
        try {
            String body = text.strip().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
            JsonNode node = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
            var riasec = new LinkedHashMap<String, Integer>();
            for (char letter : LETTERS.toCharArray()) {
                JsonNode value = node.path("riasec").path(String.valueOf(letter));
                if (!value.isNumber()) return null;
                riasec.put(String.valueOf(letter), clamp(value.asInt()));
            }
            var evidence = new LinkedHashMap<String, String>();
            node.path("riasecEvidence").fields().forEachRemaining(entry -> {
                if (LETTERS.contains(entry.getKey()) && entry.getKey().length() == 1 && grounded(entry.getValue().asText(), answers))
                    evidence.put(entry.getKey(), entry.getValue().asText().strip());
            });
            var traits = new ArrayList<Trait>();
            var names = new HashSet<String>();
            for (JsonNode item : node.path("traits")) {
                String name = item.path("name").asText("").strip();
                String quote = item.path("evidence").asText("").strip();
                double confidence = item.path("confidence").asDouble(-1);
                if (name.isEmpty() || name.codePointCount(0, name.length()) > 12 || confidence < 0 || confidence > 1
                        || !grounded(quote, answers) || !names.add(name)) continue;
                traits.add(new Trait(name, Math.round(confidence * 100) / 100.0, quote));
                if (traits.size() == 8) break;
            }
            if (traits.size() < 3) return null;
            if (traits.size() < 5) for (Trait extra : rules(answers).traits())
                if (traits.size() < 5 && names.add(extra.name())) traits.add(extra);
            var c = node.path("constraints");
            var constraints = new Constraints(bounded(c.path("city").asText(""), 50), bounded(c.path("salary").asText(""), 50),
                    bounded(c.path("overtime").asText(""), 50));
            return new Inference(riasec, evidence, List.copyOf(traits), constraints,
                    bounded(node.path("summary").asText(""), 80), "model");
        } catch (Exception ex) {
            return null;
        }
    }

    static boolean grounded(String quote, List<QA> answers) {
        if (quote == null) return false;
        String q = normalize(quote);
        if (q.codePointCount(0, q.length()) < 2) return false;
        return answers.stream().anyMatch(a -> a.answer() != null && normalize(a.answer()).contains(q));
    }

    private static String normalize(String text) {
        return text.replaceAll("[\\s\"'“”‘’「」]", "").replace('，', ',').replace('。', '.');
    }

    private static final Map<Character, List<String>> KEYWORDS = Map.of(
            'R', List.of("动手", "搭建", "组装", "硬件", "机器", "维修", "电路", "户外", "运动", "做出", "装机", "实验室"),
            'I', List.of("研究", "原理", "分析", "算法", "数学", "钻研", "弄懂", "推导", "好奇", "论文", "编程", "代码", "解决问题"),
            'A', List.of("设计", "画", "写作", "音乐", "摄影", "创作", "视频", "剪辑", "创意", "表达", "审美", "排版"),
            'S', List.of("帮助", "教", "志愿", "沟通", "倾听", "照顾", "辅导", "分享", "陪伴", "服务", "讲解"),
            'E', List.of("组织", "带领", "推动", "负责人", "竞选", "销售", "谈判", "创业", "影响", "说服", "领导", "策划", "主持"),
            'C', List.of("整理", "规划", "表格", "excel", "计划", "细致", "记录", "规范", "流程", "条理", "稳定", "核对"));
    private static final Map<String, List<String>> TRAITS = new LinkedHashMap<>();
    static {
        TRAITS.put("好奇钻研", List.of("好奇", "钻研", "研究", "弄懂", "原理"));
        TRAITS.put("细致认真", List.of("细致", "认真", "仔细", "核对", "严谨"));
        TRAITS.put("主动推动", List.of("组织", "带领", "推动", "负责人", "主动"));
        TRAITS.put("善于沟通", List.of("沟通", "倾听", "讲解", "交流", "表达"));
        TRAITS.put("有创意", List.of("创意", "设计", "创作", "想法", "点子"));
        TRAITS.put("动手实践", List.of("动手", "做出", "搭建", "实践", "折腾"));
        TRAITS.put("重视协作", List.of("团队", "合作", "协作", "一起"));
        TRAITS.put("坚持有韧性", List.of("坚持", "熬", "不放弃", "反复", "克服"));
        TRAITS.put("条理规划", List.of("计划", "规划", "整理", "条理", "安排"));
        TRAITS.put("乐于助人", List.of("帮助", "帮", "志愿", "照顾", "辅导"));
    }
    private static final Pattern CITY = Pattern.compile("(北京|上海|深圳|广州|杭州|成都|南京|武汉|西安|苏州|天津|重庆|长沙|厦门|合肥|郑州|青岛|济南|大连|宁波|无锡|珠海|东莞|福州|昆明|沈阳|哈尔滨|长春|南昌|贵阳|南宁|太原|石家庄|海口|兰州|乌鲁木齐|呼和浩特)");
    private static final Pattern SALARY = Pattern.compile("(\\d{1,3}(?:\\.\\d)?\\s*[kK千万](?:\\s*[-~到至]\\s*\\d{1,3}(?:\\.\\d)?\\s*[kK千万])?|\\d{4,6}\\s*(?:元|块)?(?:\\s*[-~到至]\\s*\\d{4,6}\\s*(?:元|块)?)?)");
    private static final Pattern OVERTIME = Pattern.compile("(不(?:想|能|接受)?加班|拒绝加班|偶尔加班|少加班|可以加班|能接受加班|接受加班|996|大小周|双休)");

    /** Deterministic keyword fallback; evidence is the sentence that contains the matched word. */
    Inference rules(List<QA> answers) {
        var sentences = new ArrayList<String>();
        for (QA qa : answers) if (qa.answer() != null)
            for (String s : qa.answer().split("[。！？!?；;\\n]")) if (!s.isBlank()) sentences.add(s.strip());
        var riasec = new LinkedHashMap<String, Integer>();
        var evidence = new LinkedHashMap<String, String>();
        for (char letter : LETTERS.toCharArray()) {
            int hits = 0;
            String first = null;
            for (String sentence : sentences) {
                String lower = sentence.toLowerCase(Locale.ROOT);
                for (String word : KEYWORDS.get(letter)) if (lower.contains(word)) { hits++; if (first == null) first = sentence; }
            }
            riasec.put(String.valueOf(letter), clamp(15 + hits * 20));
            if (first != null) evidence.put(String.valueOf(letter), quote(first));
        }
        var traits = new ArrayList<Trait>();
        for (var entry : TRAITS.entrySet()) {
            for (String sentence : sentences) {
                long hits = entry.getValue().stream().filter(sentence::contains).count();
                if (hits > 0) { traits.add(new Trait(entry.getKey(), hits > 1 ? 0.6 : 0.5, quote(sentence))); break; }
            }
            if (traits.size() == 8) break;
        }
        String all = String.join("\n", answers.stream().map(QA::answer).filter(Objects::nonNull).toList());
        return new Inference(riasec, evidence, List.copyOf(traits),
                new Constraints(first(CITY, all), first(SALARY, all), first(OVERTIME, all)), "", "rule");
    }

    private static String first(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1).strip() : "";
    }

    private static String quote(String sentence) {
        String s = sentence.strip();
        return s.codePointCount(0, s.length()) > 40 ? s.substring(0, s.offsetByCodePoints(0, 40)) : s;
    }

    private static int clamp(int value) { return Math.max(0, Math.min(100, value)); }

    private static String bounded(String text, int max) {
        String s = text == null ? "" : text.strip();
        return s.codePointCount(0, s.length()) > max ? s.substring(0, s.offsetByCodePoints(0, max)) : s;
    }
}
