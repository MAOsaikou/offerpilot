package com.offerpilot.common.error;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final boolean recoverable;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.message());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, false);
    }

    public BusinessException(ErrorCode errorCode, String message, boolean recoverable) {
        super(message);
        this.errorCode = errorCode;
        this.recoverable = recoverable;
    }

    public boolean recoverable() { return recoverable; }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
