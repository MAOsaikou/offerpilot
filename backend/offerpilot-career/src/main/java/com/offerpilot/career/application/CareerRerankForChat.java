package com.offerpilot.career.application;

/** Small application boundary: chat must approve before persisting a recommendation revision. */
public interface CareerRerankForChat {
    CareerRecommendationService.Revision revision(String owner, String id);
    CareerRecommendationService.Revision rerank(String owner, String parentId,
                                               CareerRecommendationService.Constraints constraints);
}
