package com.offerpilot.common.text;

/** Retains existing UTF-16 size limits without cutting a Unicode surrogate pair. */
public final class TextBounds {
    private TextBounds() {}

    public static String truncate(String text, int maxChars) {
        if (maxChars < 0) throw new IllegalArgumentException("maxChars must be non-negative");
        if (text.length() <= maxChars) return text;
        int end = maxChars;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) end--;
        return text.substring(0, end);
    }
}
