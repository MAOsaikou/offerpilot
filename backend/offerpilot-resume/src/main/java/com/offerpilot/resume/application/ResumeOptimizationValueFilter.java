package com.offerpilot.resume.application;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative lexical filter; apply only after production validation, never as a fact check. */
public final class ResumeOptimizationValueFilter {
    private static final Pattern WHITESPACE = Pattern.compile("[\\p{javaWhitespace}\\p{Zs}]+");
    private static final int MAX_EDITS = 2;

    private ResumeOptimizationValueFilter() {}

    /** Returns a copy, preserving retained patches and their IDs exactly. */
    public static ObjectNode filter(ObjectNode validatedReport) {
        ObjectNode result = validatedReport.deepCopy();
        var kept = result.putArray("patches");
        var filtered = result.putArray("filteredPatches");
        for (var patch : validatedReport.path("patches")) {
            String from = normalize(patch.path("from").asText());
            String value = normalize(patch.path("value").asText());
            String reason = null;
            if (from.equals(value)) {
                reason = "NORMALIZED_EQUAL";
            } else {
                int[] before = from.codePoints().toArray(), after = value.codePoints().toArray();
                // At most two code-point edits AND at most 10% of the longer normalized text.
                // Short text (<10 code points) is kept unless normalized equal.
                int limit = Math.min(MAX_EDITS, Math.max(before.length, after.length) / 10);
                if (limit > 0 && withinEditDistance(before, after, limit)) reason = "TINY_EDIT";
            }
            if (reason == null) kept.add(patch.deepCopy());
            else filtered.addObject().put("id", patch.path("id").asText())
                    .put("path", patch.path("path").asText()).put("reason", reason);
        }
        return result;
    }

    private static String normalize(String text) {
        return WHITESPACE.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    /** Banded Levenshtein over Unicode code points: O(length * limit), with linear memory. */
    private static boolean withinEditDistance(int[] before, int[] after, int limit) {
        if (Math.abs(before.length - after.length) > limit) return false;
        int unreachable = limit + 1;
        int[] previous = new int[after.length + 1], current = new int[after.length + 1];
        Arrays.fill(previous, unreachable);
        for (int j = 0; j <= Math.min(after.length, limit); j++) previous[j] = j;
        for (int i = 1; i <= before.length; i++) {
            int start = Math.max(1, i - limit), end = Math.min(after.length, i + limit);
            current[start - 1] = start == 1 ? Math.min(i, unreachable) : unreachable;
            if (end < after.length) current[end + 1] = unreachable;
            int rowMin = current[start - 1];
            for (int j = start; j <= end; j++) {
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1),
                        previous[j - 1] + (before[i - 1] == after[j - 1] ? 0 : 1));
                rowMin = Math.min(rowMin, current[j]);
            }
            if (rowMin > limit) return false;
            int[] swap = previous; previous = current; current = swap;
        }
        return previous[after.length] <= limit;
    }
}
