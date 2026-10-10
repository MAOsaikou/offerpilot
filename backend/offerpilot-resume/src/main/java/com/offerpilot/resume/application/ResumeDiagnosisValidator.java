package com.offerpilot.resume.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import java.util.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Validates citations against an immutable, contact-free version; not a semantic fact checker. */
public final class ResumeDiagnosisValidator {
    private ResumeDiagnosisValidator() {}
    private static final java.util.regex.Pattern MARKUP_TAGS = java.util.regex.Pattern.compile("</?([A-Za-z][A-Za-z0-9:_-]*)(?:\\s[^<>]*)?\\s*/?>");
    public static final Set<String> RULES = Set.of("CONTRIBUTION", "EVIDENCE", "RESULT", "CLARITY", "CONSISTENCY");
    public static boolean validCard(JsonNode report) {
        try {
            require(report.path("schemaVersion").isInt() && report.path("schemaVersion").asInt() == 2);
            for (String key : Set.of("reportId", "resumeId", "versionId", "createdAt", "model", "modelTier", "promptVersion", "ruleVersion")) text(report,key,200);
            require(report.path("versionNo").isInt() && report.path("versionNo").asInt() > 0);
            var content=new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            for (String key : Set.of("summary", "strengths", "findings")) content.set(key,report.path(key).deepCopy());
            for (var finding : content.path("findings")) {
                text(finding,"findingId",80);
                ((com.fasterxml.jackson.databind.node.ObjectNode)finding).remove("findingId");
            }
            validate(content,null);
            array(report.path("checks"),2);
            for (var check : report.path("checks")) { text(check,"path",200); text(check,"message",600); }
            array(report.path("limitations"),5);
            for (var limitation : report.path("limitations")) require(limitation.isTextual() && limitation.asText().length() <= 1000);
            return true;
        } catch (Exception ex) { return false; }
    }
    private static void envelope(JsonNode report) {
        require(report != null && report.isObject() && report.toString().length() <= 22000);
        fields(report, Set.of("summary", "strengths", "findings"));
        text(report, "summary", 1200);
        array(report.path("strengths"), 5); array(report.path("findings"), 12);
    }
    private static void structure(JsonNode report) {
        envelope(report);
        for (String section : List.of("strengths", "findings")) for (var item : report.path(section)) itemStructure(item, section);
    }
    private static void itemStructure(JsonNode item, String section) {
        if (section.equals("strengths")) {
            fields(item, Set.of("message", "evidence")); text(item, "message", 600);
        } else {
            fields(item, Set.of("ruleId", "kind", "priority", "evidence", "reason", "suggestion", "questions"));
            require(RULES.contains(item.path("ruleId").asText()));
            require(Set.of("OBSERVATION", "NEEDS_CLARIFICATION").contains(item.path("kind").asText()));
            require(Set.of("HIGH", "MEDIUM", "LOW").contains(item.path("priority").asText()));
            text(item, "reason", 1000); text(item, "suggestion", 1000);
            array(item.path("questions"), 3);
            for (var question : item.path("questions")) require(question.isTextual() && !question.asText().isBlank() && question.asText().length() <= 300);
            if ("NEEDS_CLARIFICATION".equals(item.path("kind").asText())) require(!item.path("questions").isEmpty());
        }
        evidenceShape(item.path("evidence"));
    }
    public record CitationFailure(String outputPointer, String sourcePointer, String kind) {}
    public record Salvaged(ObjectNode report, int omittedItems, List<CitationFailure> failures) {}
    public static final class ValidationFailure extends BusinessException {
        private final String feedback;
        private ValidationFailure(String feedback) {
            super(ErrorCode.STRUCTURED_OUTPUT_INVALID, invalid().getMessage());
            this.feedback = feedback;
        }
        public String feedback() { return feedback; }
    }
    /** Strict verification remains available for persisted evidence and test assertions. */
    public static void validate(JsonNode report, JsonNode source) {
        structure(report);
        require(citations(report, source).isEmpty());
    }
    /** Never relocate, paraphrase or fabricate a quote. One bad citation removes its whole item. */
    public static Salvaged salvage(JsonNode report, JsonNode source) {
        require(source != null);
        try { envelope(report); }
        catch (BusinessException ex) { throw new ValidationFailure("REPORT_STRUCTURE_INVALID: 仅允许规定字段、类型和数量；不得添加 score。输出完整 JSON 对象。"); }
        List<CitationFailure> failures = citations(report, source);
        ObjectNode verified = report.deepCopy();
        int omitted = 0;
        for (String section : List.of("strengths", "findings")) {
            var kept = verified.arrayNode();
            int index = 0;
            for (var item : report.path(section)) {
                String pointer = "/" + section + "/" + index++;
                try { itemStructure(item, section); }
                catch (BusinessException ex) { failures.add(new CitationFailure(pointer, "[item]", "ITEM_STRUCTURE_INVALID")); }
                if (failures.stream().anyMatch(f -> f.outputPointer().equals(pointer) || f.outputPointer().startsWith(pointer + "/"))) omitted++;
                else kept.add(item.deepCopy());
            }
            verified.set(section, kept);
        }
        if (verified.path("strengths").isEmpty() && verified.path("findings").isEmpty()) {
            StringBuilder feedback = new StringBuilder("NO_VERIFIED_ITEMS: 至少保留一项有逐字依据的优点或发现。以下为校验数据（不含引文）：");
            for (var failure : failures) feedback.append("\n").append(failure.outputPointer()).append(" source=")
                    .append(failure.sourcePointer()).append(" kind=").append(failure.kind());
            throw new ValidationFailure(feedback.toString());
        }
        if (omitted > 0) verified.put("summary", "已保留 " + (verified.path("strengths").size() + verified.path("findings").size())
                + " 项具有原文依据的观察；部分条目未通过结构或引用校验，已省略。请结合真实经历逐项核对建议。");
        return new Salvaged(verified, omitted, List.copyOf(failures));
    }
    private static void evidenceShape(JsonNode evidence) {
        array(evidence, 3); require(!evidence.isEmpty());
        for (var entry : evidence) {
            fields(entry, Set.of("path", "quote")); text(entry, "path", 200); text(entry, "quote", 1000);
        }
    }
    private static List<CitationFailure> citations(JsonNode report, JsonNode source) {
        var failures = new ArrayList<CitationFailure>();
        for (String section : List.of("strengths", "findings")) {
            int itemIndex = 0;
            for (var item : report.path(section)) {
                var seen = new HashSet<String>(); int citationIndex = 0;
                for (var entry : item.path("evidence")) {
                    if (citationIndex == 3) break; // Invalid oversized items are omitted by itemStructure.
                    String path = entry.path("path").asText(), quote = entry.path("quote").asText();
                    String kind = citationKind(path, quote, source);
                    if (!seen.add(path + "\n" + quote)) kind = "DUPLICATE_CITATION";
                    if (kind != null) failures.add(new CitationFailure("/" + section + "/" + itemIndex + "/evidence/" + citationIndex,
                            path.matches("/[A-Za-z0-9_/~.\\-]{0,199}") ? path : "[invalid-pointer]", kind));
                    citationIndex++;
                }
                itemIndex++;
            }
        }
        return failures;
    }
    private static String citationKind(String path, String quote, JsonNode source) {
        if (quote.isBlank()) return "EMPTY_QUOTE";
        if (!path.startsWith("/")) return "INVALID_POINTER";
        if (path.equals("/basics") || path.startsWith("/basics/") || path.equals("/confidence") || path.startsWith("/confidence/")) return "EXCLUDED_FIELD";
        JsonNode value;
        try { value = source == null ? null : source.at(path); } catch (IllegalArgumentException ex) { return "INVALID_POINTER"; }
        if (value == null) return null; // Envelope-only validation: quotes were verified before persistence.
        if (value.isMissingNode()) return "PATH_NOT_FOUND";
        if (!value.isTextual()) return "NON_STRING_FIELD";
        if (!value.asText().contains(quote)) return "QUOTE_NOT_VERBATIM";
        return crossesUnopenedMarkupBoundary(value.asText(), quote) ? "UNTRUSTED_MARKUP_SPAN" : null;
    }
    /** An orphan closing tag can escape a surrounding data boundary. Quarantine its suffix,
     * including plain-text excerpts inside it, without a role-name or instruction keyword list.
     * Balanced XML examples remain quotable. This does not classify natural-language injection. */
    private static boolean crossesUnopenedMarkupBoundary(String source, String quote) {
        var tags = MARKUP_TAGS.matcher(source);
        var opened = new ArrayDeque<String>();
        int boundary = -1;
        while (tags.find()) {
            String tag = tags.group(), name = tags.group(1);
            if (tag.startsWith("</")) {
                if (opened.isEmpty() || !opened.peek().equals(name)) { boundary = tags.start(); break; }
                opened.pop();
            } else if (!tag.endsWith("/>")) opened.push(name);
        }
        if (boundary < 0) return false;
        // A repeated quote is still eligible if it occurs in the preserved prefix.
        return !source.substring(0, boundary).contains(quote);
    }
    private static void fields(JsonNode node, Set<String> names) {
        require(node.isObject() && node.size() == names.size());
        node.fieldNames().forEachRemaining(key -> require(names.contains(key)));
    }
    private static void array(JsonNode node, int max) { require(node.isArray() && node.size() <= max); }
    private static void text(JsonNode node, String key, int max) {
        require(node.path(key).isTextual() && !node.path(key).asText().isBlank() && node.path(key).asText().length() <= max);
    }
    private static void require(boolean condition) { if (!condition) throw invalid(); }
    public static BusinessException invalid() {
        return new BusinessException(ErrorCode.STRUCTURED_OUTPUT_INVALID, "诊断的结构或原文依据未通过校验，请重试；未保存未经核实的报告");
    }
}
