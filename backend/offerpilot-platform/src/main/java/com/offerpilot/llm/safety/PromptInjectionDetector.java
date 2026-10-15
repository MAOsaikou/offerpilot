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
                + "|(?:忽略|无视|绕过|跳过)(?:[^。\\n，,；;]{0,6}?)(?:审核|校验|权限|安全检查|安全限制|脱敏)"
                + "|不要脱敏|把[^。\\n]{0,10}当(?:作|成)?最高优先级"
                + "|\\b(?:ignore|disregard|forget|override)\\b[^.\\n]{0,24}?\\b(?:previous|prior|above|earlier|all|system)\\b[^.\\n]{0,24}?\\b(?:instructions?|rules|prompts?|guidelines)\\b",
                Pattern.CASE_INSENSITIVE));
        RULES.put("ROLE_HIJACK", Pattern.compile(
                "(?:从现在(?:开始|起)|接下来)?你(?:现在)?(?:是|将是|将扮演)(?:一个)?(?:不受(?:任何)?限制|没有(?:任何)?限制|无限制|越狱)"
                + "|扮演[^。\\n]{0,6}不受[^。\\n]{0,4}(?:规则|限制|约束)|(?:暂时|先)?退出(?:求职)?助手(?:身份|角色)?|模拟(?:一个)?(?:终端|命令行|shell)"
                + "|开发者模式|越狱模式|(?-i:\\bDAN\\b)|\\bjailbreak\\b|\\bdeveloper mode\\b|\\byou are now (?:an? )?(?:unrestricted|unfiltered|DAN)\\b",
                Pattern.CASE_INSENSITIVE));
        RULES.put("EXFILTRATION", Pattern.compile(
                "(?:输出|打印|显示|告诉我|泄露|复述|重复|列出|附上|发送|发给我)[^。\\n]{0,8}?(?:系统提示|系统指令|提示词|你的(?:指令|规则|设定)|API ?Key|[A-Z_]*API_KEY|[A-Z]+_TOKEN|密钥|服务器环境变量|环境变量和密码|其他用户)"
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
                + "|这句话(?:就)?是(?:批准|授权)|视为(?:已)?批准|在这句话(?:里|中)?批准|伪造审批|(?:跳过|绕过|不经|无需|不需要)(?:任何)?(?:审批|确认|人工确认)"
                + "|(?:招聘方|管理员|开发者|系统)授权你"));
        RULES.put("PRIVILEGE_CLAIM", Pattern.compile(
                "我是(?:开发者|管理员|系统管理员|运维)[，,：:]\\s*(?:[^。\\n]{0,12}?)(?:必须|读取|所有用户|权限|总分)"
                + "|这是一条系统消息|(?:system|admin) override", Pattern.CASE_INSENSITIVE));
        RULES.put("CROSS_TENANT", Pattern.compile(
                "(?:其他|别的|所有)用户的|用户\\s*\\d+\\s*的|owner\\s*=\\s*\\S"));
        RULES.put("BULK_DESTRUCTIVE", Pattern.compile(
                "(?:删除|删掉|清空|清除)[^。\\n，,]{0,4}?(?:所有|全部)[^。\\n，,]{0,4}?(?:投递|简历|数据|看板|记录)"
                + "|(?:所有|全部)(?:的)?(?:投递|简历|看板|数据)[^。\\n，,]{0,4}?(?:删掉|删除|清空)"
                + "|(?:批量)?发送所有简历"));
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
            case "FAKE_APPROVAL" -> "冒充批准或要求跳过确认";
            case "PRIVILEGE_CLAIM" -> "冒充开发者或系统";
            case "CROSS_TENANT" -> "试图访问其他用户的数据";
            case "BULK_DESTRUCTIVE" -> "要求批量删除或外发";
            default -> "可疑指令";
        };
    }
}
