package com.offerpilot.agent.orchestrator;

import com.offerpilot.agent.domain.*;
import java.util.*;
import java.util.regex.Pattern;

/** Whole-request grammar only. Unknown cities, employers, constraints and extra actions fall back. */
final class ReadOnlyRuleRoute {
    private static final Pattern CAREER = Pattern.compile(
            "(?:我(?:想|希望)(?:探索|了解)(?:一下)?(?:适合自己的)?职业(?:方向)?"
            + "|(?:请)?(?:帮我)?探索(?:一下)?职业(?:方向)?"
            + "|我(?:还)?不知道(?:自己)?适合(?:什么|哪种)职业"
            + "|我适合(?:什么|哪种)职业)"
            + "(?:[，,](?:怎么|如何)开始探索|[，,]从哪里开始)?[？?。]?"
            + "(?:(?:请)?(?:用)?(?:一句话|简短)(?:指导|回答|说明)?[。！!]?)?");
    private static final String CITIES = "北京|上海|广州|深圳|杭州|成都|武汉|南京|苏州|西安|重庆|天津|长沙|郑州|合肥|厦门|福州|济南|青岛|大连|沈阳|宁波|无锡|东莞|佛山|珠海";
    private static final String TECH = "(?i:Java|Python|C\\+\\+|C#|Go|Golang|Rust|PHP|Ruby|JavaScript|TypeScript|Android|iOS)";
    private static final String ROLE = "(?:后端(?:开发)?|前端(?:开发)?|全栈(?:开发)?|软件开发|软件测试|测试开发|算法|机器学习|深度学习|数据分析|数据开发|数据工程|数据科学|产品(?:经理|助理)|用户研究|交互设计|视觉设计|运维|信息安全|网络安全|嵌入式(?:开发)?)";
    private static final Pattern JOB = Pattern.compile(
            "(?:请)?(?:帮我)?(?:找|寻找|搜|搜索|推荐)(?:一下)?\\s*"
            + "(?:(?<city>" + CITIES + ")(?:市)?(?:的)?\\s*)?"
            + "(?<query>(?:" + TECH + "\\s*(?:" + ROLE + ")?|" + ROLE + ")(?:工程师)?)\\s*"
            + "(?<nature>实习|校招|社招)?(?:岗位|职位|工作)?[。！!？?]?");

    private ReadOnlyRuleRoute() {}

    static Optional<PlanStep> parse(String content) {
        if (content == null || content.length() > 100) return Optional.empty();
        String text = content.strip();
        if (CAREER.matcher(text).matches()) return Optional.of(new PlanStep("s1", "职业兴趣推荐",
                ExpertType.CAREER_DISCOVERY, Map.of("action", "recommend", "request", content), List.of()));
        var match = JOB.matcher(text);
        if (!match.matches()) return Optional.empty();
        String nature = match.group("nature");
        // Full-time is not equivalent to social recruitment. The current search API cannot express
        // that constraint, so requests containing 全职 deliberately fall back to the model parser.
        return Optional.of(new PlanStep("s1", "搜索真实岗位", ExpertType.JOB, Map.of(
                "action", "search", "query", match.group("query").strip(), "site", "",
                "nature", nature == null ? "all" : switch (nature) {
                    case "实习" -> "intern"; case "校招" -> "campus"; default -> "social";
                }, "location", match.group("city") == null ? "" : match.group("city")), List.of()));
    }
}
