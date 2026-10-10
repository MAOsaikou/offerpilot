package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;

import java.util.LinkedHashSet;

/**
 * 把已经校验过的匹配报告映射成 job_match 卡片。
 * 不生成维度分，也不新造报告 id。
 */
public final class JobMatchCards {
    public static final String CARD_TYPE = "job_match";

    public record Card(String cardId, String cardType, JsonNode payload, String summary) {
    }

    private JobMatchCards() {
    }

    public static Card fromReport(JsonNode report) {
        if (!(report instanceof ObjectNode source) || !source.path("id").isTextual() || source.path("id").asText().isBlank()) {
            throw new BusinessException(ErrorCode.STRUCTURED_OUTPUT_INVALID, "匹配报告缺少标识");
        }
        ObjectNode payload = source.deepCopy();
        JsonNode job = payload.path("jobSnapshot");
        if (!job.isMissingNode() && !job.isNull()) {
            payload.set("evaluation", JobEvalReport.evaluate(payload, job));
        }
        payload.remove("jobSnapshot");
        payload.remove("resumeSnapshot");
        ArrayNode matched = payload.putArray("matched");
        ArrayNode gaps = payload.putArray("gaps");
        ArrayNode advice = payload.putArray("advice");
        LinkedHashSet<String> suggestions = new LinkedHashSet<>();
        JsonNode requirements = payload.path("requirements");
        if (requirements.isArray()) {
            for (JsonNode item : requirements) {
                String status = item.path("status").asText("");
                String requirement = item.path("requirement").asText("");
                String suggestion = item.path("suggestion").asText("");
                if (!suggestion.isBlank()) suggestions.add(suggestion);
                if ("MET".equals(status)) {
                    matched.addObject()
                            .put("requirement", requirement)
                            .put("evidence", item.path("resumeQuote").asText(""))
                            .put("strength", "STRONG");
                } else if ("PARTIAL".equals(status) || "MISSING".equals(status)) {
                    gaps.addObject()
                            .put("requirement", requirement)
                            .put("severity", "MEDIUM")
                            .put("suggestion", suggestion);
                }
            }
        }
        suggestions.forEach(advice::add);
        return new Card(payload.path("id").asText(), CARD_TYPE, payload, summary(payload));
    }

    private static String summary(ObjectNode payload) {
        String text = payload.path("summary").asText("").strip();
        if (payload.path("score").isNumber()) {
            String score = "匹配分 " + payload.path("score").asInt();
            text = text.isEmpty() ? score : score + "。" + text;
        }
        if (text.isBlank()) return "已生成校验过的岗位匹配报告";
        return text;
    }
}
