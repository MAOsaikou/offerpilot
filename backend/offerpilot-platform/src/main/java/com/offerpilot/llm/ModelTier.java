package com.offerpilot.llm;

import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public enum ModelTier {
    FLASH,
    PRO,
    VISION_ALT;

    private static final Logger log = LoggerFactory.getLogger(ModelTier.class);

    public static ModelTier from(String raw) { return from(raw, FLASH); }

    /** Historical preferences and old clients remain readable after CHEAP was removed. */
    public static ModelTier from(String raw, ModelTier defaultTier) {
        if (raw == null || raw.isBlank()) return defaultTier;
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "FLASH", "FAST", "快速", "CHEAP", "ECONOMY", "经济", "BALANCED" -> FLASH;
            case "PRO", "DEEP", "深度", "深入" -> PRO;
            case "VISION_ALT", "VISION" -> VISION_ALT;
            default -> {
                // Do not log arbitrary client input (it may contain sensitive data).
                log.warn("Unknown model tier; using default tier {}", defaultTier);
                yield defaultTier;
            }
        };
    }
}
