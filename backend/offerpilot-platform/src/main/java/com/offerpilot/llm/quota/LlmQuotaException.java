package com.offerpilot.llm.quota;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;

public class LlmQuotaException extends BusinessException {
    private final long retryAfterSeconds;
    public LlmQuotaException(ErrorCode code, long retryAfterSeconds) {
        super(code);
        this.retryAfterSeconds = retryAfterSeconds;
    }
    public long retryAfterSeconds() { return retryAfterSeconds; }
}
