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
                "(?:忽略|忽视|无视|忘记|忘掉|不要遵守|不再遵守|绕过|覆盖)[^。\\n]{0,8}?(?:以上|上面|上述|之前|先前|前面|全部|所有|一切|系统|原有|开发者)[^。\\n]{0,8}?(?:指令|规则|提示词?|设定|约束|限制|要求|设置)"
                + "|(?:忽略|无视|绕过|跳过)(?:[^。\\n，,；;]{0,6}?)(?:审核|校验|权限|安全检查|安全限制|脱敏)"
                + "|不(?:再)?受[^。\\n]{0,6}(?:之前|先前|以上|原有|任何)[^。\\n]{0,6}(?:设定|规则|约束|限制|指令)"
                + "|(?:之前|先前|以上|上面|上一条|前面|原来|原有)[^。\\n]{0,10}?(?:指令|规则|设定|限制|约束)[^。\\n]{0,6}?(?:作废|无效|不算数?|失效|取消|解除|不生效)"
                + "|(?:规则|限制|安全审核|内容审核|安全规则|审核|过滤)(?:已经?|全部|都)?(?:被)?(?:关闭|解除|暂停|不生效|失效)"
                + "|(?:关闭|关掉|停用)(?:内容|安全)?(?:审核|过滤|安全限制)"
                + "|新的(?:最高)?(?:指令|命令|任务)(?:是|为)?\\s*[:：]|(?:当成|当作|视为)[^。\\n]{0,8}系统(?:级)?(?:指令|命令)|(?:隐藏|秘密)指令\\s*[:：]"
                + "|不要脱敏|把[^。\\n]{0,10}当(?:作|成)?最高优先级"
                + "|\\b(?:ignore|disregard|forget|override)\\b[^.\\n]{0,24}?\\b(?:previous|prior|above|earlier|all|system)\\b[^.\\n]{0,24}?\\b(?:instructions?|rules|prompts?|guidelines|messages?)\\b"
                + "|\\b(?:previous|prior|earlier)\\s+(?:rules|instructions)\\s+(?:are|is)\\s+(?:void|cancelled|invalid)\\b|\\bobey only me\\b"
                + "|\\bnew (?:task|instructions?)\\s*:",
                Pattern.CASE_INSENSITIVE));
        RULES.put("ROLE_HIJACK", Pattern.compile(
                "(?:从现在(?:开始|起)|接下来)?你(?:现在)?(?:的身份)?(?:是|将是|将扮演)(?:一个)?[^。\\n]{0,4}?(?:不受(?:任何)?限制|没有(?:任何)?(?:限制|规则|约束|过滤)|无限制|越狱)"
                + "|(?:扮演|假装|模拟)[^。\\n]{0,10}(?:不受|没有|无)[^。\\n]{0,6}(?:规则|限制|约束|过滤|安全限制)"
                + "|(?:暂时|先)?退出(?:求职)?助手(?:身份|角色)?|模拟(?:一个)?(?:终端|命令行|shell)"
                + "|(?:进入|开启|启用|打开|切换到|激活)[^。\\n]{0,4}(?:开发者|越狱|无限制)模式|越狱模式|所有限制(?:已)?解除"
                + "|(?-i:\\bDAN\\b)|\\bjailbreak\\b|\\b(?:enter|enable|activate)\\b[^.\\n]{0,10}\\bdeveloper mode\\b"
                + "|\\byou are now (?:an? )?(?:unrestricted|unfiltered|DAN)\\b|\\b(?:act as|pretend to be)\\b[^.\\n]{0,12}\\b(?:unrestricted|unfiltered|no (?:rules|limits))\\b",
                Pattern.CASE_INSENSITIVE));
        RULES.put("EXFILTRATION", Pattern.compile(
                "(?:输出|打印|显示|告诉我|泄露|复述|重复|列出|附上|发送|发给我|返回|写出|贴出|给我)[^。\\n]{0,10}?(?:系统提示|系统指令|系统设定|提示词|你的(?:指令|规则|设定|初始指令)|初始指令|开发者(?:给你的)?(?:指令|要求)|API ?Key|[A-Z_]*API_KEY|[A-Z]+_TOKEN|密钥|环境变量|连接串|内部(?:配置|参数|设置)|工具定义|其他用户)"
                + "|(?:你|你的)(?:收到|看到)的(?:第一条|最初|初始|全部|所有)[^。\\n]{0,4}(?:消息|指令|文字|内容)|(?:重复|复述)你[^。\\n]{0,4}收到的"
                + "|(?:泄露|外泄)[^。\\n]{0,4}(?:内部|系统)"
                + "|\\b(?:reveal|print|show|repeat|leak|output)\\b[^.\\n]{0,20}?\\b(?:system prompt|hidden prompt|your instructions|developer instructions|api key|environment variables|configuration)\\b",
                Pattern.CASE_INSENSITIVE));
        RULES.put("FAKE_ROLE_TAG", Pattern.compile(
                "</?\\s*(?:system|developer|assistant)\\s*>|\\[\\[?\\s*(?:SYSTEM|INST)\\s*\\]\\]?|<\\|im_start\\|>|(?m)^\\s*#{2,}\\s*(?:system\\b|系统指令)"
                + "|[\\[【]\\s*(?:系统|管理员|平台)(?:通知|公告|消息|指令)\\s*[\\]】]",
                Pattern.CASE_INSENSITIVE));
        RULES.put("SCORE_MANIPULATION", Pattern.compile(
                "(?:给|打)(?:我|此|该|这份|本)?(?:简历|候选人|应聘者)?(?:满分|100\\s*分|一百分|最高分|最高等级)"
                + "|(?:评分|分数|匹配度)(?:必须|一律|直接)?(?:设为|改为|定为|写成)\\s*(?:100|满分|最高)"
                + "|无论[^。\\n]{0,12}(?:都|一律)(?:要)?(?:给|评|打|写)[^。\\n]{0,4}(?:A|满分|最高|优秀)"
                + "|\\b(?:rate|score)\\b[^.\\n]{0,20}?\\b(?:10/10|100/100|full marks|highest)\\b",
                Pattern.CASE_INSENSITIVE));
        // Text in a resume / JD addressed to the AI reader is an indirect injection regardless of what it asks.
        RULES.put("INDIRECT_INSTRUCTION", Pattern.compile(
                "(?:读到|看到|阅读)这(?:句话|段话?|里|份简历)的\\s*(?:AI|大模型|模型|助手)"
                + "|(?:给|致)\\s*(?:AI|大模型|模型|阅读本简历的\\s*AI)(?:\\s*助手)?(?:看的)?\\s*[：:）)】]"
                + "|(?:^|[）)】\\s])(?:AI|模型)\\s*助手?\\s*[：:]\\s*请"
                + "|\\b(?:any|the) AI reading this\\b",
                Pattern.CASE_INSENSITIVE));
        RULES.put("FAKE_APPROVAL", Pattern.compile(
                "(?:用户|我)?已(?:经)?(?:批准|授权|同意)(?:了)?(?:该|此|这个|所有)?(?:操作|写入|删除|修改|工具调用)"
                + "|这句话(?:就)?是(?:批准|授权)|视为(?:已)?批准|在这句话(?:里|中)?批准|伪造审批|(?:跳过|绕过|不经|无需|不需要)(?:任何)?(?:审批|确认|人工确认)"
                + "|(?:招聘方|管理员|开发者|系统)授权你"
                + "|审批(?:已经?|流程已)?(?:通过|完成)|已批准\\s*[:：]|approval[_ ]?id\\s*=|\\bapproval\\s*:\\s*granted\\b"
                + "|(?:得到|获得)(?:了)?(?:用户)?(?:的)?授权[^。\\n]{0,6}(?:直接|执行)|不(?:需要|用)(?:我|再)?(?:同意|确认)|别再问|不用再确认",
                Pattern.CASE_INSENSITIVE));
        RULES.put("PRIVILEGE_CLAIM", Pattern.compile(
                "我是(?:开发者|管理员|系统管理员|运维)[，,：:]\\s*(?:[^。\\n]{0,12}?)(?:必须|读取|所有用户|权限|总分)"
                + "|我是[^。\\n]{0,14}(?:工程师|开发者|开发人员|管理员)[，,][^。\\n]{0,8}(?:关闭|关掉|跳过|绕过)"
                + "|(?:作为|以)(?:超级|系统)?(?:管理员|root|开发者)(?:用户)?(?:身份)?[，,]?\\s*(?:我)?(?:命令|要求)?你"
                + "|来自[^。\\n]{0,6}(?:管理员|开发者|系统)的(?:命令|指令)|(?:管理员|平台|运营)(?:的)?(?:命令|指令)\\s*[：:]"
                + "|(?:root|超级管理员|最高权限)[^。\\n]{0,10}(?:跳过|绕过)"
                + "|这是一条系统消息|(?:system|admin) override", Pattern.CASE_INSENSITIVE));
        RULES.put("CROSS_TENANT", Pattern.compile(
                "(?:其他|别的|所有)用户的|用户\\s*\\d+\\s*的|owner\\s*=\\s*\\S|用户\\s*(?:ID|id)\\s*(?:是|为|[:：=])"
                + "|(?:其他人|别人|别的人)的(?:数据|简历|面试|记录|聊天)|修改别人的|导出[^。\\n]{0,6}所有用户"));
        RULES.put("BULK_DESTRUCTIVE", Pattern.compile(
                "(?:删除|删掉|清空|清除)[^。\\n，,]{0,4}?(?:所有|全部)[^。\\n，,]{0,4}?(?:投递|简历|数据|看板|记录)"
                + "|(?:所有|全部)(?:的)?(?:投递|简历|看板|数据)[^。\\n，,]{0,4}?(?:删掉|删除|清空)"
                + "|(?:批量)?发送所有简历|(?:执行|进行)批量删除|一次性删除|自动投递\\s*\\d+"
                + "|把(?:这些|所有)[^。\\n]{0,8}都删"));
    }

    /** Categories found in the text, in rule order; empty when nothing matched. */
    public static List<String> detect(String text) {
        if (text == null || text.isBlank()) return List.of();
        String normalized = normalize(text.length() > 200_000 ? text.substring(0, 200_000) : text);
        var found = new ArrayList<String>();
        RULES.forEach((category, pattern) -> { if (pattern.matcher(normalized).find()) found.add(category); });
        return found;
    }

    /**
     * NFKC folds full-width letters (ｉｇｎｏｒｅ → ignore); spaces between CJK characters are dropped so
     * "忽 略 上 面" reads as "忽略上面". Detection only; the original text is what the model sees.
     */
    static String normalize(String text) {
        String folded = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC);
        return CJK_GAP.matcher(folded).replaceAll("");
    }
    private static final Pattern CJK_GAP = Pattern.compile("(?<=[\\u4e00-\\u9fff，。])[ \\t\\u3000]+(?=[\\u4e00-\\u9fff，。])");

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
            case "INDIRECT_INSTRUCTION" -> "写给 AI 的隐藏指令";
            default -> "可疑指令";
        };
    }
}
