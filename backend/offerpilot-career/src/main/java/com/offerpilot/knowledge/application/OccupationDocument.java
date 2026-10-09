package com.offerpilot.knowledge.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/** One source document for embeddings and PostgreSQL simple fulltext; no generated synonyms. */
public final class OccupationDocument {
    private static final ObjectMapper JSON = new ObjectMapper();
    private OccupationDocument() {}

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
