package com.offerpilot.knowledge.application;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class OccupationRrf {
    private OccupationRrf() {}
    @SafeVarargs
    public static Map<String, Double> scores(List<String>... ranks) {
        Map<String, Double> scores = new HashMap<>();
        for (List<String> rank : ranks) {
            var unique = rank.stream().distinct().toList();
            for (int i = 0; i < unique.size(); i++) scores.merge(unique.get(i), 1.0 / (60 + i + 1), Double::sum);
        }
        return scores;
    }
}
