package com.offerpilot.ops;
/** Optional local integration seam. No external transport is installed by default. */
public interface OpsNotificationChannel {
    void notify(String code, boolean active);
}
