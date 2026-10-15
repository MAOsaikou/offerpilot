package com.offerpilot.knowledge.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class OccupationRrf {
    /**
     * Semantic retrieval leads when a real embedding exists; simple-tokenizer fulltext (Han bigrams, OR)
     * and the RIASEC interest prior only reinforce it. With equal weights the Bailian live eval
     * (docs/research/2026-10-16-occupation-rag-bailian-live.md) dropped vector-only Hit@3 19/19 to 14/19.
     */
    public static final double LEXICAL_WITH_VECTOR = 0.5;
    public static final double PROFILE_WITH_VECTOR = 0.5;

    private OccupationRrf() {}

    @SafeVarargs
    public static Map<String, Double> scores(List<String>... ranks) {
        double[] weights = new double[ranks.length];
        java.util.Arrays.fill(weights, 1.0);
        return weighted(weights, ranks);
    }

    @SafeVarargs
    public static Map<String, Double> weighted(double[] weights, List<String>... ranks) {
        if (weights.length != ranks.length) throw new IllegalArgumentException("One weight per rank list");
        Map<String, Double> scores = new HashMap<>();
        for (int r = 0; r < ranks.length; r++) {
            var unique = ranks[r].stream().distinct().toList();
            for (int i = 0; i < unique.size(); i++) scores.merge(unique.get(i), weights[r] / (60 + i + 1), Double::sum);
        }
        return scores;
    }
}
