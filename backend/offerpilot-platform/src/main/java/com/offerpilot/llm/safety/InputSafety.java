package com.offerpilot.llm.safety;

import java.util.regex.Pattern;

/**
 * SC-03 input screen for the chat. Two outcomes need a fixed, model-independent answer:
 * a user who may be in crisis gets support resources first, and a request to forge credentials
 * or to seriously harm someone is declined with a safe alternative without calling the model.
 * Patterns favour precision: venting about job-search stress is not a crisis by itself.
 */
public final class InputSafety {
    private InputSafety() {}

    public enum Concern { CRISIS, FORGERY, VIOLENCE }

    private static final Pattern CRISIS = Pattern.compile(
            "(?:想|要|打算|准备|好想|真想)(?:去)?(?:自杀|轻生|寻死|去死|结束(?:自己的?)?生命|了结自己)"
            + "|不想活(?:了|下去)|活着(?:没|没有)(?:意思|意义)了?|(?:想|要)?自残|割腕|跳楼(?:算了|了结)"
            + "|\\b(?:kill myself|end my life|want to die|suicidal)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FORGERY = Pattern.compile(
            "(?:伪造|造假|做假|作假|编造|虚构|假造|P图|PS一?[张份个]?|弄一?[张份个]?假的?|做一?[张份个]?假的?|开一?[张份个]?假的?)"
            + "[^。\\n]{0,8}?(?:学历|学位|毕业证|学位证|证书|成绩单|离职证明|在职证明|实习证明|收入证明|工资流水|银行流水|录用通知|offer|背调|推荐信|工作经历|实习经历|项目经历|工作年限)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VIOLENCE = Pattern.compile(
            "(?:制作|自制|合成|怎么做|如何做|教我做)[^。\\n]{0,4}?(?:炸弹|炸药|爆炸物|毒药|枪支|冰毒|毒品)"
            + "|(?:报复|弄死|杀了|杀掉|捅)[^。\\n]{0,4}?(?:面试官|HR|hr|老板|上司|领导|同事)");

    public static Concern screen(String text) {
        if (text == null || text.isBlank()) return null;
        if (CRISIS.matcher(text).find()) return Concern.CRISIS;
        if (FORGERY.matcher(text).find()) return Concern.FORGERY;
        if (VIOLENCE.matcher(text).find()) return Concern.VIOLENCE;
        return null;
    }

    /** Shown before the model's reply. 12356 is the national psychological assistance hotline. */
    public static final String CRISIS_SUPPORT = "听起来你现在承受着很大的压力，谢谢你愿意说出来。你的安全比任何求职进度都重要。\n\n"
            + "如果你有伤害自己的想法，请现在就联系可以陪你的人：\n"
            + "- 全国心理援助热线 **12356**（免费，各地均已开通）\n"
            + "- 情况紧急时请拨打 **120** 或 **110**，或请身边信任的人陪你去最近的医院\n\n"
            + "我也会在这里陪你聊一聊。\n\n";

    /** Added to the system prompt so the model follows up gently instead of continuing job tasks. */
    public static final String CRISIS_GUIDANCE = "用户可能正处于心理危机。热线与急救信息已在回复开头展示，不要重复号码。"
            + "用温和、简短的语言共情，询问用户此刻是否安全、身边是否有人；不要说教，不要评判，不要推进简历、岗位或面试任务，"
            + "不要给出任何可能用于自我伤害的信息。";

    public static String refusal(Concern concern) {
        return switch (concern) {
            case FORGERY -> "这个我不能帮忙：伪造或虚构学历、证书、证明材料和经历属于造假，入职背调和材料核验时很容易被发现，可能被取消录用甚至承担法律责任。\n\n"
                    + "我可以换个方式帮你：把真实的课程项目、比赛、社团或实习经历挖掘出亮点，用 STAR 写得更有说服力；"
                    + "或者针对目标岗位找出差距，规划一两个短期可完成的真实项目来补齐。要不要从你现有的经历开始？";
            case VIOLENCE -> "这个请求我不能协助。如果你和面试官、HR 或同事之间发生了让你很愤怒的事，我可以帮你梳理经过、"
                    + "想想怎么沟通或通过正规渠道（如申诉、劳动仲裁）维护权益。如果你担心自己或他人的安全，请拨打 110。";
            case CRISIS -> CRISIS_SUPPORT;
        };
    }
}
