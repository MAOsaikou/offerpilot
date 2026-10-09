package com.offerpilot.llm.quota;

/** Implemented by identity; the gateway never queries another domain's tables. */
public interface LlmQuotaIdentity {
    record Policy(boolean guest, long dailyTokenLimit) {}
    Policy policy(String owner);
}
