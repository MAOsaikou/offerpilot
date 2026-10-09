package com.offerpilot.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Shared transport policy; domain controllers still own their event payloads. */
@Component
public class SseStreams {
    private final ObjectMapper json;
    private final Duration heartbeatInterval;

    public SseStreams(ObjectMapper json,
                      @Value("${offerpilot.sse.heartbeat-interval:PT15S}") Duration heartbeatInterval) {
        if (heartbeatInterval.isZero() || heartbeatInterval.isNegative())
            throw new IllegalArgumentException("SSE heartbeat interval must be positive");
        this.json = json;
        this.heartbeatInterval = heartbeatInterval;
    }

    public SseWriter open(HttpServletResponse response, long timeout, String runId,
                          String failureMessage, Runnable onDisconnect) {
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        response.setCharacterEncoding("UTF-8");
        return new SseWriter(json, new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(timeout),
                heartbeatInterval, runId, failureMessage, onDisconnect);
    }
}
