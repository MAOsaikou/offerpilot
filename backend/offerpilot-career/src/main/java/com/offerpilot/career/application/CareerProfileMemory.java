package com.offerpilot.career.application;

/** Application port implemented by the Agent's durable-memory adapter, never by a second store. */
public interface CareerProfileMemory {
    record Seed(String target, String city, String major) {}
    Seed read(String owner);
    void sync(String owner, String target, String city, String major, String hollandCode);
    /** Assessment changes own only interest code and an explicitly provided city. */
    void syncAssessment(String owner, String city, String hollandCode);
}
