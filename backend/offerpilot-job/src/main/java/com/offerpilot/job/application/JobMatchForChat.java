package com.offerpilot.job.application;

/** 聊天执行器调用已有匹配。不暴露岗位仓储或实体。 */
public interface JobMatchForChat {
    JobMatchCards.Card match(String owner, String jobId, String resumeId, String resumeVersionId, String requestId);
    default JobMatchCards.Card match(String owner, String jobId, String resumeId, String resumeVersionId,
                                     String requestId, com.offerpilot.llm.ModelTier tier) {
        return match(owner, jobId, resumeId, resumeVersionId, requestId);
    }
}
