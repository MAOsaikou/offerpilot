package com.offerpilot.career.application;

import com.fasterxml.jackson.databind.JsonNode;

/** Full interest strengths in RIASEC order, retained before deriving display letters. */
public record RiasecProfile(double r, double i, double a, double s, double e, double c) {
    public RiasecProfile {
        for (double value : new double[]{r, i, a, s, e, c}) {
            if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid RIASEC strength");
        }
    }

    public static RiasecProfile fromJson(JsonNode node) {
        double[] values = new double[6];
        for (int n = 0; n < 6; n++) {
            JsonNode value = node.path(String.valueOf("RIASEC".charAt(n)));
            if (!value.isNumber()) throw new IllegalArgumentException("Missing RIASEC dimension");
            values[n] = value.asDouble();
        }
        return new RiasecProfile(values[0], values[1], values[2], values[3], values[4], values[5]);
    }

    public double correlation(RiasecProfile other) {
        // Pearson compares relative interest strengths across all six dimensions. Centering and
        // scale invariance let chip counts and O*NET ratings use different baselines/scales without
        // rewarding uniformly high occupation ratings. A flat profile supplies no preference (r=0).
        double[] left = {r, i, a, s, e, c};
        double[] right = {other.r, other.i, other.a, other.s, other.e, other.c};
        double meanLeft = java.util.Arrays.stream(left).average().orElseThrow();
        double meanRight = java.util.Arrays.stream(right).average().orElseThrow();
        double covariance = 0, varianceLeft = 0, varianceRight = 0;
        for (int n = 0; n < 6; n++) {
            double x = left[n] - meanLeft, y = right[n] - meanRight;
            covariance += x * y;
            varianceLeft += x * x;
            varianceRight += y * y;
        }
        if (varianceLeft == 0 || varianceRight == 0) return 0;
        return Math.max(-1, Math.min(1, covariance / Math.sqrt(varianceLeft * varianceRight)));
    }
}
