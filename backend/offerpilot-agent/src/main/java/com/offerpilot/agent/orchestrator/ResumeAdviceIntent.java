package com.offerpilot.agent.orchestrator;

import java.util.regex.Pattern;

/** A small whole-request grammar, deliberately excluding supplied prose, extra actions and negation. */
final class ResumeAdviceIntent {
    private static final Pattern QUESTION = Pattern.compile(
            "简历(?:里|中)?的(?:项目经历|实习经历|工作经历|教育经历|个人总结)"
                    + "(?:应该|该)?(?:怎么|如何)写(?:更有说服力|更好)?[？?。]?"
                    + "(?:给[两三23]条(?:要点|建议)[。！!]?)?");

    private ResumeAdviceIntent() {}

    static boolean matches(String content) {
        return content != null && content.length() <= 80 && QUESTION.matcher(content.strip()).matches();
    }
}
