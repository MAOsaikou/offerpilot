package com.offerpilot.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    OK(0, HttpStatus.OK, "ok"),

    VALIDATION_FAILED(10001, HttpStatus.BAD_REQUEST, "参数校验失败"),
    FILE_TYPE_UNSUPPORTED(10002, HttpStatus.BAD_REQUEST, "文件类型不支持"),
    FILE_TOO_LARGE(10003, HttpStatus.PAYLOAD_TOO_LARGE, "文件过大"),
    JSON_PARSE_FAILED(10004, HttpStatus.BAD_REQUEST, "JSON 解析失败"),

    UNAUTHENTICATED(20001, HttpStatus.UNAUTHORIZED, "未登录"),
    TOKEN_EXPIRED(20002, HttpStatus.UNAUTHORIZED, "令牌过期"),
    TOKEN_INVALID(20003, HttpStatus.UNAUTHORIZED, "令牌无效"),
    FORBIDDEN(20004, HttpStatus.FORBIDDEN, "无权限"),
    BAD_CREDENTIALS(20005, HttpStatus.UNAUTHORIZED, "账号或密码错误"),
    EMAIL_TAKEN(20006, HttpStatus.CONFLICT, "邮箱已注册"),
    DEMO_NOT_ALLOWED(20007, HttpStatus.FORBIDDEN, "体验账号不允许该操作"),

    CONVERSATION_NOT_FOUND(30101, HttpStatus.NOT_FOUND, "会话不存在"),
    RUN_NOT_FOUND(30201, HttpStatus.NOT_FOUND, "运行不存在"),
    RUN_ALREADY_FINISHED(30202, HttpStatus.CONFLICT, "运行已结束"),
    APPROVAL_EXPIRED(30203, HttpStatus.CONFLICT, "审批不存在或已过期"),
    CONCURRENT_RUN_LIMIT(30204, HttpStatus.TOO_MANY_REQUESTS, "并发运行超限"),
    RESUME_BUSY(30401, HttpStatus.CONFLICT, "简历正在解析，请稍后再试"),
    RESUME_NOT_FOUND(30402, HttpStatus.NOT_FOUND, "简历不存在或无权访问"),
    RESUME_VERSION_CONFLICT(30403, HttpStatus.CONFLICT, "简历已更新，请重新加载后保存"),
    RESUME_INVALID(30404, HttpStatus.BAD_REQUEST, "简历文件无效或无法读取"),
    JOB_NOT_FOUND(30501, HttpStatus.NOT_FOUND, "岗位不存在，请重新搜索"),
    JOB_CONTENT_MISSING(30502, HttpStatus.UNPROCESSABLE_ENTITY, "岗位详情不足，暂不能生成匹配报告，请查看企业官网"),
    MATCH_NOT_FOUND(30503, HttpStatus.NOT_FOUND, "匹配报告不存在或无权访问"),

    MODEL_TIMEOUT(40001, HttpStatus.GATEWAY_TIMEOUT, "模型调用超时"),
    MODEL_RATE_LIMITED(40002, HttpStatus.BAD_GATEWAY, "模型限流"),
    STRUCTURED_OUTPUT_INVALID(40003, HttpStatus.UNPROCESSABLE_ENTITY, "结构化输出校验失败"),
    CONTENT_BLOCKED(40004, HttpStatus.UNPROCESSABLE_ENTITY, "内容审核未通过"),
    TOOL_FAILED(40005, HttpStatus.BAD_GATEWAY, "工具执行失败"),
    TOOL_CALL_LIMIT(40006, HttpStatus.TOO_MANY_REQUESTS, "工具调用超限"),
    APPROVAL_REJECTED(40007, HttpStatus.UNPROCESSABLE_ENTITY, "用户拒绝审批"),
    DAILY_QUOTA_EXCEEDED(40008, HttpStatus.TOO_MANY_REQUESTS, "每日配额用尽"),
    MODEL_NOT_CONFIGURED(40009, HttpStatus.SERVICE_UNAVAILABLE, "模型未配置，请配置 API Key 后重启后端"),
    MODEL_UNAVAILABLE(40010, HttpStatus.BAD_GATEWAY, "模型调用失败，请检查 API Key、账户余额、模型名和网络后重试"),

    UNKNOWN(50000, HttpStatus.INTERNAL_SERVER_ERROR, "未知错误"),
    DATABASE_UNAVAILABLE(50001, HttpStatus.SERVICE_UNAVAILABLE, "数据库不可用"),
    STORAGE_UNAVAILABLE(50002, HttpStatus.SERVICE_UNAVAILABLE, "存储不可用"),
    CACHE_UNAVAILABLE(50003, HttpStatus.SERVICE_UNAVAILABLE, "缓存不可用");

    private final int code;
    private final HttpStatus status;
    private final String message;

    ErrorCode(int code, HttpStatus status, String message) {
        this.code = code;
        this.status = status;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
