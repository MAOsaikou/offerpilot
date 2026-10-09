package com.offerpilot.common.error;

import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadSize(HttpServletRequest request) {
        return ResponseEntity.status(413).body(ApiResponse.fail(ErrorCode.FILE_TOO_LARGE.code(),
                "简历文件不能超过 10MB", TraceIdFilter.current(request)));
    }

    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingFile(HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.VALIDATION_FAILED.code(),
                "请选择需要上传的文件", TraceIdFilter.current(request)));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex, HttpServletRequest request) {
        ErrorCode error = ex.errorCode();
        var response = ResponseEntity.status(error.status()).contentType(MediaType.APPLICATION_JSON);
        if (ex instanceof com.offerpilot.llm.quota.LlmQuotaException quota)
            response.header("Retry-After", String.valueOf(quota.retryAfterSeconds()));
        return response
                .body(ApiResponse.fail(error.code(), ex.getMessage(), TraceIdFilter.current(request)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        List<ApiResponse.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toFieldError)
                .toList();
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(ApiResponse.fail(
                ErrorCode.VALIDATION_FAILED.code(),
                ErrorCode.VALIDATION_FAILED.message(),
                TraceIdFilter.current(request),
                errors
        ));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraint(
            ConstraintViolationException ex,
            HttpServletRequest request
    ) {
        List<ApiResponse.FieldError> errors = ex.getConstraintViolations().stream()
                .map(v -> new ApiResponse.FieldError(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(ApiResponse.fail(
                ErrorCode.VALIDATION_FAILED.code(),
                ErrorCode.VALIDATION_FAILED.message(),
                TraceIdFilter.current(request),
                errors
        ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(
            HttpMessageNotReadableException ex,
            HttpServletRequest request
    ) {
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(ApiResponse.fail(
                ErrorCode.JSON_PARSE_FAILED.code(),
                ErrorCode.JSON_PARSE_FAILED.message(),
                TraceIdFilter.current(request)
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception traceId={}", TraceIdFilter.current(request), ex);
        return ResponseEntity.internalServerError().contentType(MediaType.APPLICATION_JSON).body(ApiResponse.fail(
                ErrorCode.UNKNOWN.code(),
                ErrorCode.UNKNOWN.message(),
                TraceIdFilter.current(request)
        ));
    }

    private static ApiResponse.FieldError toFieldError(FieldError error) {
        String reason = error.getDefaultMessage() == null ? "invalid" : error.getDefaultMessage();
        return new ApiResponse.FieldError(error.getField(), reason);
    }
}
