package com.offerpilot.knowledge.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** One source document for embeddings and PostgreSQL simple fulltext; no generated synonyms. */
public final class OccupationDocument {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern TOKENS = Pattern.compile("\\p{IsHan}+|[\\p{L}\\p{N}&&[^\\p{IsHan}]]+", Pattern.UNICODE_CHARACTER_CLASS);
    private OccupationDocument() {}

    /** Generic Han bigrams work with PostgreSQL simple without a zhparser installation or lexicon.
     * Keep Latin skills intact. Use OR for free-text queries so unrelated clauses do not require
     * every term in one document; ts_rank_cd rewards actual token coverage and proximity.
     */
    public static List<String> lexicalTokens(String text) {
        List<String> tokens = new ArrayList<>();
        var matcher = TOKENS.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            int[] points = word.codePoints().toArray();
            if (Character.UnicodeScript.of(points[0]) == Character.UnicodeScript.HAN) {
                if (points.length == 1) tokens.add(word);
                for (int n = 1; n < points.length; n++) tokens.add(new String(points, n - 1, 2));
            } else tokens.add(word);
        }
        return tokens;
    }

    public static String fulltext(String document) {
        return document + "\n" + String.join(" ", lexicalTokens(document));
    }

    public static String lexicalQuery(String query) {
        return lexicalTokens(query).stream().distinct().map(token -> "'" + token + "'")
                .collect(java.util.stream.Collectors.joining(" | "));
    }

    public static String build(String name, String description, String skillsJson, String growth,
                               String family, String[] titles) {
        return "name: " + text(name) + "\ndescription: " + text(description)
                + "\nskills: " + skills(skillsJson) + "\ngrowth: " + text(growth)
                + "\nfamily: " + text(family) + "\ntitles: " + String.join(" / ", titles);
    }

    public static String skills(String value) {
        if (value == null) return "";
        try {
            List<String> leaves = new ArrayList<>();
            flatten(JSON.readTree(value), leaves);
            return String.join(" / ", leaves);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid occupation skills JSON", ex);
        }
    }

    private static void flatten(JsonNode node, List<String> leaves) {
        if (node == null || node.isNull()) return;
        if (node.isContainerNode()) node.forEach(child -> flatten(child, leaves));
        else leaves.add(node.asText());
    }

    public static String excerpt(String description, String skillsJson) {
        String value = text(description).strip();
        if (value.isEmpty()) value = skills(skillsJson);
        value = value.replaceAll("\\s+", " ");
        return value.substring(0, value.offsetByCodePoints(0, Math.min(120, value.codePointCount(0, value.length()))));
    }

    private static String text(String value) { return value == null ? "" : value; }
}
