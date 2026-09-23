package com.offerpilot.career.application;

import java.util.Locale;

/**
 * 霍兰德兴趣码的位置加权相似度（docs/04 §2.3 规则层的打分规则）。
 *
 * 打分方式：用户的第 1/2/3 码分别权重 3/2/1。
 * - 字母与职业码处于同一位置：拿满权重
 * - 字母出现在职业码的其他位置：按 0.5 折算
 * - 字母不在职业码里：不得分
 *
 * 满分 6 分（3+2+1）。纯函数，不依赖数据库，便于单测。
 */
public final class HollandMatcher {
    private static final int[] WEIGHTS = {3, 2, 1};
    private static final double SHIFTED_POSITION_FACTOR = 0.5;

    private HollandMatcher() {
    }

    public static double score(String userCode, String occupationCode) {
        String user = normalize(userCode);
        String occupation = normalize(occupationCode);
        if (user.isEmpty() || occupation.isEmpty()) return 0;

        double total = 0;
        for (int i = 0; i < Math.min(WEIGHTS.length, user.length()); i++) {
            int position = occupation.indexOf(user.charAt(i));
            if (position < 0) continue;
            total += WEIGHTS[i] * (position == i ? 1.0 : SHIFTED_POSITION_FACTOR);
        }
        return total;
    }

    /**
     * 取兴趣码前三位。职业条目里 holland_code 已经是三位，
     * 用户画像可能给出六维分数，需要按分数降序取前三。
     */
    public static String topThree(double realistic, double investigative, double artistic,
                                  double social, double enterprising, double conventional) {
        String[] letters = {"R", "I", "A", "S", "E", "C"};
        double[] scores = {realistic, investigative, artistic, social, enterprising, conventional};
        Integer[] order = {0, 1, 2, 3, 4, 5};
        java.util.Arrays.sort(order, (a, b) -> {
            int byScore = Double.compare(scores[b], scores[a]);
            return byScore != 0 ? byScore : Integer.compare(a, b); // 同分时按 RIASEC 固定顺序，保证结果稳定
        });
        StringBuilder code = new StringBuilder(3);
        for (int i = 0; i < 3; i++) code.append(letters[order[i]]);
        return code.toString();
    }

    /** 去掉分隔符、统一大写，避免 "i r c" 这类写法影响比较。 */
    public static String normalize(String code) {
        if (code == null) return "";
        StringBuilder letters = new StringBuilder(3);
        for (char c : code.toUpperCase(Locale.ROOT).toCharArray()) {
            if (c >= 'A' && c <= 'Z') letters.append(c);
        }
        return letters.toString();
    }
}
