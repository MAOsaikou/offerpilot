package com.offerpilot.resume.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import java.util.*;

/** Structural/evidence checks, including a conservative per-line numeric preservation rule. */
public final class ResumeOptimizationValidator {
    private ResumeOptimizationValidator() {}
    public static void validate(JsonNode output, JsonNode original, String jd) {
        require(output != null && output.isObject() && output.size() == 2 && output.has("summary") && output.has("patches"));
        require(output.path("summary").isTextual() && !output.path("summary").asText().isBlank() && output.path("summary").asText().length() <= 1000);
        var patches = output.path("patches");
        require(patches.isArray() && patches.size() <= 12 && output.toString().length() <= 24000);
        if (!patches.isEmpty()) {
            try { ResumePatches.validateBatch(original, patches); } catch (BusinessException ex) { throw invalid(); }
            for (var patch : patches) {
                String quote = patch.path("jdEvidence").asText();
                require(patch.path("jdEvidence").isTextual() && !quote.isBlank() && jd.contains(quote));
                require(numbers(patch.path("from").asText()).containsAll(numbers(patch.path("value").asText())));
            }
        }
    }
    private static Set<String> numbers(String text) {
        return com.offerpilot.common.text.NumericProvenance.numbers(text);
    }
    private static void require(boolean valid) { if (!valid) throw invalid(); }
    public static BusinessException invalid() {
        return new BusinessException(ErrorCode.STRUCTURED_OUTPUT_INVALID, "改写建议的结构、原文或岗位依据未通过校验，请重试；未保存未经核实的建议");
    }
}
