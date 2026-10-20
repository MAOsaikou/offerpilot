package com.offerpilot.common.notification;

/** Business producers supply an owner and stable source key; delivery joins their transaction. */
public interface NotificationSink {
    void send(String owner, String kind, String key, String title, String body, String link);
}
