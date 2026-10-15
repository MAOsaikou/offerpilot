package com.offerpilot.llm.safety;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SC-01 rule layer: recognises the common shapes of prompt injection in user input and in content we
 * pass to the model (uploaded resumes, imported job pages, knowledge notes, tool results). Detection
 * never blocks by itself; the gateway tells the model which fragments are suspected injections and to
 * treat them strictly as data, and import flows show the user a notice. High precision over recall:
 * ordinary requests such as "忽略格式问题" or "请扮演面试官" must not match.
 */
public final class PromptInjectionDetector {
    private PromptInjectionDetector() {}

    private static final Map<String, Pattern> RULES = new LinkedHashMap<>();
    static {
        RULES.put("OVERRIDE", Pattern.compile(
                "(?:忽略|无视|忘记|忘掉|不要遵守|不再遵守|绕过|覆盖)[^。\\n]{0,8}?(?:以上|上面|上述|之前|先前|前面|全部|所有|系统|原有)[^。\\n]{0,8}?(?:指令|规则|提示词?|设定|约束|限制|要求)"
                + "|\\b(?:ignore|disregard|forget|override)\\b[^.\\n]{0,24}?\\b(?:previous|prior|above|earlier|all|system)\\b[^.\\n]{0,24}?\\b(?:instructions?|rules|prompts?|guidelines)\\b",
                Pattern.CASE_INSENSITIVE));
        RULES.put("ROLE_HIJACK", Pattern.compile(
                "(?:从现在(?:开始|起)|接下来)?你(?:现在)?(?:是|将是|将扮演)(?:一个)?(?:不受(?:任何)?限制|没有(?:任何)?限制|无限制|越狱)"
                + "|开发者模式|越狱模式|(?-i:\\bDAN\\b)|\\bjailbreak\\b|\\bdeveloper mode\\b|\\byou are now (?:an? )?(?:unrestricted|unfiltered|DAN)\\b",
                Pattern.CASE_INSENSITIVE));
        RULES.put("EXFILTRATION", Pattern.compile(
                "(?:输出|打印|显示|告诉我|泄露|复述|重复|列出)[^。\\n]{0,8}?(?:系统提示|系统指令|提示词|你的(?:指令|规则|设定)|API ?Key|密钥|环境变量|其他用户)"
                + "|\\b(?:reveal|print|show|repeat|leak)\\b[^.\\n]{0,20}?\\b(?:system prompt|your instructions|api key|environment variables)\\b",
                Pattern.CASE_INSENSITIVE));
        RULES.put("FAKE_ROLE_TAG", Pattern.compile(
                "</?\\s*(?:system|developer|assistant)\\s*>|\\[\\[?\\s*(?:SYSTEM|INST)\\s*\\]\\]?|<\\|im_start\\|>|(?m)^\\s*#{2,}\\s*(?:system\\b|系统指令)",
                Pattern.CASE_INSENSITIVE));
        RULES.put("SCORE_MANIPULATION", Pattern.compile(
                "(?:给|打)(?:我|此|该|这份|本)?(?:简历|候选人|应聘者)?(?:满分|100\\s*分|一百分|最高分|最高等级)"
                + "|(?:评分|分数|匹配度)(?:必须|一律|直接)?(?:设为|改为|定为|写成)\\s*(?:100|满分|最高)"
                + "|\\b(?:rate|score)\\b[^.\\n]{0,20}?\\b(?:10/10|100/100|full marks|highest)\\b",
                Pattern.CASE_INSENSITIVE));
        RULES.put("FAKE_APPROVAL", Pattern.compile(
                "(?:用户|我)?已(?:经)?(?:批准|授权|同意)(?:了)?(?:该|此|这个|所有)?(?:操作|写入|删除|修改|工具调用)"
                + "|这句话(?:就)?是(?:批准|授权)|视为(?:已)?批准"));
    }

    /** Categories found in the text, in rule order; empty when nothing matched. */
    public static List<String> detect(String text) {
        if (text == null || text.isBlank()) return List.of();
        String normalized = text.length() > 200_000 ? text.substring(0, 200_000) : text;
        var found = new ArrayList<String>();
        RULES.forEach((category, pattern) -> { if (pattern.matcher(normalized).find()) found.add(category); });
        return found;
    }

    /** User-facing label for a notice on imported content. */
    public static String label(String category) {
        return switch (category.toUpperCase(Locale.ROOT)) {
            case "OVERRIDE" -> "要求忽略系统规则";
            case "ROLE_HIJACK" -> "试图改变助手身份";
            case "EXFILTRATION" -> "索取系统提示或密钥";
            case "FAKE_ROLE_TAG" -> "伪造系统角色标记";
            case "SCORE_MANIPULATION" -> "要求直接给高分";
            case "FAKE_APPROVAL" -> "冒充用户批准";
            default -> "可疑指令";
        };
    }
}
