package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.springframework.stereotype.Service;

/** Take-home markdown for A–F job evaluation (deterministic; not a hiring prediction). */
@Service
public class JobEvalMarkdownExporter {
    private final JobMatchService matches;

    public JobEvalMarkdownExporter(JobMatchService matches) {
        this.matches = matches;
    }

    public String markdown(String owner, String matchId) {
        JsonNode report = matches.get(owner, matchId);
        JsonNode eval = report.path("evaluation");
        if (!eval.isObject() || !eval.path("dimensions").isArray()) {
            throw new BusinessException(ErrorCode.MATCH_NOT_FOUND, "匹配报告缺少评估卡");
        }
        var out = new StringBuilder();
        String title = text(eval, "title");
        String company = text(eval, "company");
        out.append("# ").append(safe(title.isBlank() ? "岗位评估" : title));
        if (!company.isBlank()) out.append(" · ").append(safe(company));
        out.append(" · A–F 评估\n\n");
        out.append("- 匹配报告：").append(safe(text(eval, "matchId").isBlank() ? matchId : text(eval, "matchId"))).append('\n');
        out.append("- 综合分：").append(eval.path("overall").asInt()).append(" / 100\n");
        out.append("- 等级：").append(safe(text(eval, "grade"))).append('\n');
        out.append("- 评分版本：").append(safe(text(eval, "scoringVersion"))).append("\n\n");
        out.append("> ").append(safe(text(eval, "note"))).append("\n\n");
        out.append("## 摘要\n\n").append(safe(text(eval, "summary"))).append("\n\n");
        out.append("## A–F 六维\n\n");
        for (JsonNode dim : eval.path("dimensions")) {
            out.append("### ").append(safe(text(dim, "code"))).append(' ')
                    .append(safe(text(dim, "name")))
                    .append("（").append(dim.path("score").asInt()).append("/100）\n\n");
            out.append(safe(text(dim, "reason"))).append("\n\n");
        }
        out.append("## 建议\n\n");
        JsonNode advice = eval.path("advice");
        if (advice.isArray() && advice.size() > 0) {
            for (JsonNode line : advice) out.append("- ").append(safe(line.asText(""))).append('\n');
        } else {
            out.append("- （无）\n");
        }
        out.append('\n');
        return out.toString();
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("");
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "";
        return value.replace("\r\n", "\n").strip();
    }
}
