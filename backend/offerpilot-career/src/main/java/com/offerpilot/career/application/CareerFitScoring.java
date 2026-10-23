package com.offerpilot.career.application;

import com.offerpilot.knowledge.application.OccupationDocument;

import java.util.*;
import java.util.regex.Pattern;

/** career-fit-v1: bounded lexical evidence, never a claim of proficiency or hiring probability. */
final class CareerFitScoring {
    static final double MIN_FIT = .60;
    private static final Pattern GOAL = Pattern.compile("(?:希望|目标|想做|想成为|成为|从事|从业|理想|want to|aspire to)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> STOP = Set.of("工程", "程师", "专业", "科学", "技术", "资深", "高级", "大三", "希望", "成为", "五年", "年内", "平时", "喜欢", "项目", "开发", "engineer", "senior");
    private static final List<String> BACKEND = List.of("后端", "服务端", "backend", "back-end");
    private static final List<String> SOFTWARE = List.of("软件开发", "软件工程", "软件研发", "程序员", "编程", "写代码", "coding", "programming", "software developer", "software development");
    private static final List<String> COMPUTER = List.of("计算机", "computer science");

    record Context(String target, String major, String skills, String story, String request) {
        static Context from(CareerProfileService.Profile profile, CareerAssessmentService.Assessment assessment, String request) {
            var answers = assessment == null ? List.<String>of() : assessment.answers();
            String goals = answers.stream().flatMap(a -> Arrays.stream(a.split("[。；;！!\\n]")))
                    .filter(a -> GOAL.matcher(a).find()).collect(java.util.stream.Collectors.joining(" "));
            String major = profile.major() + " " + (answers.isEmpty() ? "" : answers.get(0));
            String story = String.join(" ", profile.interests()) + " " + String.join(" ", answers);
            return new Context(profile.target() + " " + goals, major,
                    profile.skills() + " " + String.join(" ", answers.stream().skip(1).limit(3).toList()), story,
                    Objects.toString(request, ""));
        }
        String query() { return target + " " + major + " " + skills + " " + story + " " + request; }
    }

    record Catalog(String id, String name, String family, String code, RiasecProfile riasec,
                   String description, String skills, String titles, String aliases, String majors) {
        String titleText() { return name + " " + aliases + " " + titles; }
        String document() { return titleText() + " " + family + " " + description + " " + skills; }
    }

    record Score(double total, double interest, double target, double major, double skills,
                 double interestWeight, double targetWeight, double majorWeight, double skillsWeight) {
        String explanation() {
            return String.format(Locale.ROOT,
                    "实际综合排序分 %.4f = %.4f×兴趣 %.4f + %.4f×目标 %.4f + %.4f×专业 %.4f + %.4f×自述技能 %.4f；career-fit-v1，最低 %.2f，非能力或录用概率",
                    total, interestWeight, interest, targetWeight, target, majorWeight, major, skillsWeight, skills, MIN_FIT)
                    + unused("目标", targetWeight) + unused("专业", majorWeight) + unused("自述技能", skillsWeight);
        }

        private static String unused(String dimension, double weight) {
            return weight == 0 ? "；" + dimension + "未使用（缺失或当前候选集无匹配）" : "";
        }
    }

    /** Resolve dimension weights once over the whole candidate set, before filtering or limiting. */
    static List<Score> score(RiasecProfile profile, Context user, List<Catalog> candidates) {
        var matches = candidates.stream().map(catalog -> match(user, catalog)).toList();
        double wt = matches.stream().anyMatch(match -> match.target() > 0) ? .30 : 0;
        double wm = matches.stream().anyMatch(match -> match.major() > 0) ? .15 : 0;
        double ws = matches.stream().anyMatch(match -> match.skills() > 0) ? .10 : 0;
        double sum = .45 + wt + wm + ws;
        return java.util.stream.IntStream.range(0, candidates.size()).mapToObj(index -> {
            var match = matches.get(index);
            double interest = (profile.correlation(candidates.get(index).riasec()) + 1) / 2;
            double total = (.45 * interest + wt * match.target() + wm * match.major() + ws * match.skills()) / sum;
            return new Score(total, interest, match.target(), match.major(), match.skills(),
                    .45 / sum, wt / sum, wm / sum, ws / sum);
        }).toList();
    }

    private record Match(double target, double major, double skills) {}

    private static Match match(Context user, Catalog catalog) {
        double target = relevance(user.target(), catalog.titleText());
        // Backend is a direction within software development, not a fabricated catalog occupation.
        if (contains(user.target(), BACKEND)) {
            if (contains(catalog.titleText(), BACKEND)) target = 1;
            else if (contains(catalog.titleText(), SOFTWARE)) target = Math.max(target, .85);
            else if (contains(catalog.family(), SOFTWARE)) target = Math.max(target, .65);
        }
        double major = relevance(user.major(), catalog.majors());
        if (contains(user.major(), COMPUTER) && contains(catalog.document(), COMPUTER)) major = Math.max(major, .75);
        if ((contains(user.major(), COMPUTER) || contains(user.major(), SOFTWARE))
                && contains(catalog.document(), SOFTWARE)) major = Math.max(major, .75);
        double skills = relevance(user.skills(), catalog.skills() + " " + catalog.description());
        if ((contains(user.skills(), SOFTWARE) || contains(user.skills(), BACKEND))
                && contains(catalog.titleText() + " " + catalog.family(), SOFTWARE)) skills = 1;
        return new Match(target, major, skills);
    }

    private static boolean contains(String text, List<String> terms) {
        String lower = text.toLowerCase(Locale.ROOT);
        return terms.stream().anyMatch(term -> lower.contains(term));
    }
    private static Set<String> terms(String text) {
        var tokens = new LinkedHashSet<>(OccupationDocument.lexicalTokens(Objects.toString(text, "")));
        tokens.removeAll(STOP);
        return tokens;
    }
    private static double relevance(String query, String document) {
        var terms = terms(query);
        if (terms.isEmpty()) return 0;
        var available = terms(document);
        long count = terms.stream().filter(available::contains).count();
        // Two matching distinct terms suffice for full lexical support; long personal stories
        // must not dilute a concrete major or skill. Latin terms keep token boundaries (Java != JavaScript).
        return Math.min(1, count / (double) Math.min(2, terms.size()));
    }
}
