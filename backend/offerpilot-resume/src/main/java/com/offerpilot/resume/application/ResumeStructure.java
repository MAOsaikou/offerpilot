package com.offerpilot.resume.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import java.util.Map;
import java.util.Set;

/** One schema for model output and manual edits; no coercion or silent field loss. */
public final class ResumeStructure {
    private ResumeStructure() {}
    public static final String SCHEMA = """
        {"basics":{"name":"","title":"","phone":"","email":"","city":"","links":[]},
        "education":[{"school":"","degree":"","major":"","start":"","end":"","gpa":""}],
        "experience":[{"org":"","title":"","start":"","end":"","bullets":[]}],
        "projects":[{"name":"","role":"","start":"","end":"","tech":[],"bullets":[]}],
        "skills":[{"category":"","items":[]}],"certificates":[],"summary":"","confidence":{}}
        """;
    private static final Map<String, Set<String>> SECTIONS = Map.of(
        "education", Set.of("school", "degree", "major", "start", "end", "gpa"),
        "experience", Set.of("org", "title", "start", "end", "bullets"),
        "projects", Set.of("name", "role", "start", "end", "tech", "bullets"),
        "skills", Set.of("category", "items"));
    private static final Set<String> ARRAYS = Set.of("links", "bullets", "tech", "items");

    public static JsonNode validate(JsonNode root) {
        require(root != null && root.isObject() && root.toString().length() <= 100_000);
        fields(root, Set.of("basics", "education", "experience", "projects", "skills", "certificates", "summary", "confidence"));
        JsonNode basics = root.path("basics");
        require(basics.isObject());
        // Older saved versions and clients may omit the optional title.
        if (!basics.has("title")) ((com.fasterxml.jackson.databind.node.ObjectNode) basics).put("title", "");
        object(basics, Set.of("name", "title", "phone", "email", "city", "links"));
        for (var section : SECTIONS.entrySet()) {
            JsonNode array = root.path(section.getKey());
            require(array.isArray() && array.size() <= 50);
            array.forEach(item -> object(item, section.getValue()));
        }
        strings(root.path("certificates"));
        text(root.path("summary"));
        JsonNode confidence = root.get("confidence");
        if (confidence != null) {
            require(confidence.isObject() && confidence.size() <= 200);
            confidence.fields().forEachRemaining(e -> require(e.getKey().length() <= 150 &&
                e.getValue().isNumber() && e.getValue().doubleValue() >= 0 && e.getValue().doubleValue() <= 1));
        }
        return root;
    }
    private static void fields(JsonNode node, Set<String> allowed) {
        node.fieldNames().forEachRemaining(key -> require(allowed.contains(key)));
    }
    private static void object(JsonNode node, Set<String> allowed) {
        require(node.isObject()); fields(node, allowed);
        for (String key : allowed) {
            if (ARRAYS.contains(key)) strings(node.path(key)); else text(node.path(key));
        }
    }
    private static void strings(JsonNode node) {
        require(node.isArray() && node.size() <= 100); node.forEach(ResumeStructure::text);
    }
    private static void text(JsonNode node) { require(node.isTextual() && node.textValue().length() <= 5000); }
    private static void require(boolean valid) {
        if (!valid) throw new BusinessException(ErrorCode.STRUCTURED_OUTPUT_INVALID, "简历结构不符合要求，请检查字段格式与长度后重试");
    }
}
