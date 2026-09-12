package com.offerpilot.llm;

public enum ModelTier {
    FLASH,
    CHEAP,
    PRO,
    VISION_ALT;

    public static ModelTier from(String raw) {
        if (raw == null || raw.isBlank()) {
            return FLASH;
        }
        return switch (raw.trim().toUpperCase()) {
            case "FLASH" -> FLASH;
            case "CHEAP" -> CHEAP;
            case "PRO" -> PRO;
            case "VISION_ALT", "VISION" -> VISION_ALT;
            default -> throw new IllegalArgumentException("未知模型档位: " + raw);
        };
    }
}
