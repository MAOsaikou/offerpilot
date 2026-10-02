package com.offerpilot.resume.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import java.util.*;
import java.util.regex.Pattern;

/** Structural/evidence checks, including a conservative per-line numeric preservation rule. */
public final class ResumeOptimizationValidator {
    private ResumeOptimizationValidator() {}
    private static final String HAN_NUMBER="[零〇一二两三四五六七八九十百千万亿]+";
    private static final Pattern NUMBERS = Pattern.compile("[0-9０-９]+(?:[.,．][0-9０-９]+)*(?:[%％])?|百分之"+HAN_NUMBER
        +"|"+HAN_NUMBER+"(?:个|项|人|名|次|倍|套|年|月|日|天|周|秒|毫秒)");
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
        Set<String> values = new HashSet<>(); var matcher = NUMBERS.matcher(text);
        while (matcher.find()) values.add(matcher.group());
        return values;
    }
    private static void require(boolean valid) { if (!valid) throw invalid(); }
    public static BusinessException invalid() {
        return new BusinessException(ErrorCode.STRUCTURED_OUTPUT_INVALID, "改写建议的结构、原文或岗位依据未通过校验，请重试；未保存未经核实的建议");
    }
}
