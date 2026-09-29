package com.offerpilot.resume.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import java.util.Set;

/** Validates citations against an immutable, contact-free version; not a semantic fact checker. */
public final class ResumeDiagnosisValidator {
    private ResumeDiagnosisValidator() {}
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
    public static void validate(JsonNode report, JsonNode source) {
        require(report != null && report.isObject() && report.toString().length() <= 22000);
        fields(report, Set.of("summary", "strengths", "findings"));
        text(report, "summary", 1200);
        array(report.path("strengths"), 5);
        for (var strength : report.path("strengths")) {
            fields(strength, Set.of("message", "evidence")); text(strength, "message", 600);
            evidence(strength.path("evidence"), source);
        }
        array(report.path("findings"), 12);
        for (var finding : report.path("findings")) {
            fields(finding, Set.of("ruleId", "kind", "priority", "evidence", "reason", "suggestion", "questions"));
            require(RULES.contains(finding.path("ruleId").asText()));
            require(Set.of("OBSERVATION", "NEEDS_CLARIFICATION").contains(finding.path("kind").asText()));
            require(Set.of("HIGH", "MEDIUM", "LOW").contains(finding.path("priority").asText()));
            evidence(finding.path("evidence"), source);
            text(finding, "reason", 1000); text(finding, "suggestion", 1000);
            array(finding.path("questions"), 3);
            for (var question : finding.path("questions")) require(question.isTextual() && !question.asText().isBlank() && question.asText().length() <= 300);
            if ("NEEDS_CLARIFICATION".equals(finding.path("kind").asText())) require(!finding.path("questions").isEmpty());
        }
    }
    private static void evidence(JsonNode evidence, JsonNode source) {
        array(evidence, 3); require(!evidence.isEmpty());
        var seen = new java.util.HashSet<String>();
        for (var entry : evidence) {
            fields(entry, Set.of("path", "quote")); text(entry, "path", 200); text(entry, "quote", 1000);
            String path = entry.path("path").asText(), quote = entry.path("quote").asText();
            require(path.startsWith("/") && !path.startsWith("/basics") && !path.startsWith("/confidence") && seen.add(path + "\n" + quote));
            if (source == null) continue; // Persisted card envelope; original citations were checked before saving the report.
            JsonNode value;
            try { value = source.at(path); } catch (IllegalArgumentException ex) { throw invalid(); }
            require(value.isTextual() && value.asText().contains(quote));
        }
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
