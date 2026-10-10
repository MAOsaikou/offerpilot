package com.offerpilot.llm.quota;

import java.time.Instant;

public interface LlmUsageStore {
    // 0 = admitted, 1 = RPM exhausted, 2 = daily tokens exhausted.
    int check(String owner, long minute, String day, int rpm, long dailyLimit,
              Instant minuteReset, Instant dayReset, boolean consume);
    void erase(String owner);
    void addTokens(String owner, String day, long tokens, Instant dayReset);
}
