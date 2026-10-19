package com.offerpilot.artifact;

import com.offerpilot.career.application.InterviewLearningSource;
import com.offerpilot.interview.application.InterviewService;
import com.offerpilot.interview.application.InterviewRubric;
import com.offerpilot.common.error.*;
import org.springframework.stereotype.Service;

@Service
public class InterviewLearningBridge implements InterviewLearningSource {
    private final InterviewService interviews;
    public InterviewLearningBridge(InterviewService interviews) { this.interviews = interviews; }
    public Report read(String owner, String reportId) {
        var session = interviews.getForUpdate(owner, reportId);
        if (!session.completed()) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请先完成面试再生成学习计划。");
        var report = interviews.report(owner, reportId);
        return new Report(session.id(), session.role(), report.recommendedLearning().stream()
                .map(item -> new Topic(item.topic(), item.reason(), item.estimatedHours())).toList());
    }
}
