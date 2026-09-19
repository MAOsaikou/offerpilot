package com.offerpilot.auth.application;

/** Authentication boundary: callers receive only the verified owner ID. */
public interface CurrentUser {
    String require(String authorization);
}
