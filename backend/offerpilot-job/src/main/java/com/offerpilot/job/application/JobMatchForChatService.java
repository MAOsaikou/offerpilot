package com.offerpilot.job.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class JobMatchForChatService implements JobMatchForChat {
    private static final Pattern REQUEST_ID = Pattern.compile("[a-zA-Z0-9-]{16,80}");
    private final JobMatchService matches;

    public JobMatchForChatService(JobMatchService matches) {
        this.matches = matches;
    }

    @Override
    public JobMatchCards.Card match(String owner, String jobId, String resumeId, String resumeVersionId, String requestId) {
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请求标识无效");
        }
        return JobMatchCards.fromReport(matches.create(owner, jobId, resumeId, resumeVersionId, requestId));
    }
}
