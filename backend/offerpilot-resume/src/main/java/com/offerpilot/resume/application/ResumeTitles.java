package com.offerpilot.resume.application;

import com.fasterxml.jackson.databind.JsonNode;

/** A title that is still the uploaded filename can take the parsed name and objective. */
public final class ResumeTitles {
    private ResumeTitles() {}

    public static String stem(String fileName) {
        if (fileName == null) return "";
        String name = fileName.replace('\\', '/').strip();
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return name.strip();
    }

    public static boolean usesFilename(String fileName, String title) {
        if (title == null || fileName == null) return false;
        String current = title.strip();
        String stem = stem(fileName);
        String base = fileName.replace('\\', '/').strip();
        int slash = base.lastIndexOf('/');
        if (slash >= 0) base = base.substring(slash + 1).strip();
        return !stem.isEmpty() && (current.equals(stem) || current.equals(base));
    }

    /** Null when the stored title should stay as the user left it. */
    public static String suggest(String fileName, String title, JsonNode structured) {
        if (!usesFilename(fileName, title) || structured == null || !structured.isObject()) return null;
        String name = structured.path("basics").path("name").asText("").strip();
        String role = structured.path("basics").path("title").asText("").strip();
        String next = !name.isEmpty() && !role.isEmpty() ? name + " · " + role : !name.isEmpty() ? name : role;
        if (next.isEmpty() || next.equals(title.strip())) return null;
        int points = next.codePointCount(0, next.length());
        return points <= 100 ? next : next.substring(0, next.offsetByCodePoints(0, 100));
    }
}
