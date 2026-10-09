package com.offerpilot.career.application;

import java.util.List;

/** Application boundary for deterministic chat recommendations and approved target adoption. */
public interface CareerRecommendForChat {
    record Recommendation(String occupationId, String name, String family, String hollandCode,
                          double similarity, List<String> reasons, List<OccupationRecommender.Citation> citations) {
        public Recommendation(String occupationId, String name, String family, String hollandCode,
                              double similarity, List<String> reasons) {
            this(occupationId, name, family, hollandCode, similarity, reasons, List.of());
        }
    }
    record Payload(String hollandCode, List<String> interestsUsed, List<Recommendation> recommendations) {}
    record Result(String guidance, Payload payload) {}
    Result recommend(String owner);
    default Result recommend(String owner, String request) { return recommend(owner); }
    OccupationRecommender.Ranked occupation(String occupationId, String name);
    CareerProfileService.Profile adopt(String owner, String occupationId, String name);
}
