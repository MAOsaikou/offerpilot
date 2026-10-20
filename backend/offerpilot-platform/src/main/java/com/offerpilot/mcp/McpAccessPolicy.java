package com.offerpilot.mcp;

/** Optional owner-scoped client policy, implemented in the application assembly. */
public interface McpAccessPolicy {
    void requireEnabled(String owner, String provider);
}
