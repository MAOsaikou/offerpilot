package com.offerpilot.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.job.application.JobMatcher;
import com.offerpilot.job.application.JobService;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.resume.application.ResumeDiagnoser;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

/** Text-only external adapter: deliberately has no owner, repository or persistent report service. */
@Service
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true")
public class OfferPilotMcpTools {
    private final ResumeDiagnoser diagnoser;
    private final JobMatcher matcher;
    private final JobService jobs;
    private final ModelGateway gateway;
    private final ObjectMapper json;

    public OfferPilotMcpTools(ResumeDiagnoser diagnoser, JobMatcher matcher, JobService jobs,
                             ModelGateway gateway, ObjectMapper json) {
        this.diagnoser = diagnoser;
        this.matcher = matcher;
        this.jobs = jobs;
        this.gateway = gateway;
        this.json = json;
    }

    @Tool(name = "resume.diagnose", description = "只读简历诊断：分析提供的文本，不保存报告。Read-only diagnosis summary from supplied resume text. Returns top issues and strengths count; no overall score is invented. Optional JD adds a separate match summary. Does not save a diagnosis or read user records.")
    public ObjectNode diagnoseResume(
            @ToolParam(description = "必填简历纯文本 / Required resume plain text, 1–50000 characters") String resumeText,
            @ToolParam(description = "可选岗位描述 / Optional job description, at most 20000 characters", required = false) String jobDescription) {
        String resume = required(resumeText, "resumeText", 50000);
        String jd = optional(jobDescription, "jobDescription", 20000);
        var report = diagnoser.diagnose(resumeSource(resume), gateway.defaultTier()).block();
        var result = json.createObjectNode();
        result.put("summary", report.path("summary").asText());
        result.putNull("overallScore"); // The live diagnosis contract intentionally has no numerical grade.
        result.put("strengthsCount", report.path("strengths").size());
        result.put("issuesCount", report.path("findings").size());
        var issues = result.putArray("topIssues");
        for (String priority : new String[]{"HIGH", "MEDIUM", "LOW"}) {
            for (var finding : report.path("findings")) {
                if (issues.size() < 5 && priority.equals(finding.path("priority").asText())) {
                    issues.addObject().put("title", finding.path("reason").asText())
                            .put("severity", priority).put("ruleId", finding.path("ruleId").asText());
                }
            }
        }
        result.putArray("limitations").add("仅分析提供的纯文本，不评价原始排版；基础诊断不输出总分。");
        if (!jd.isEmpty()) {
            var match = matchValidated(resume, jd);
            result.putObject("jobMatch").put("score", match.path("score").asInt())
                    .put("summary", match.path("summary").asText());
        }
        return result;
    }

    @Tool(name = "jobs.search", description = "只读岗位搜索：返回公开岗位或明确标注的离线示例，含短摘录与来源。Read-only search of public company career-site jobs or explicitly marked offline sample snapshots. Returns compact items (id/url/title/company/locations/excerpt) and source metadata. May refresh the public job cache; never writes applications or private user data.")
    public ObjectNode searchJobs(
            @ToolParam(description = "必填搜索关键词（参数名query） / Required search query, 1–100 characters") String query,
            @ToolParam(description = "可选企业站点ID（如meituan/bytedance），省略搜索全部 / Optional company site ID, at most 60 characters", required = false) String site,
            @ToolParam(description = "招聘类型：校招campus/实习intern/社招social/全部all / campus, intern, social or all (case-insensitive); default all", required = false) String nature,
            @ToolParam(description = "可选城市（如北京） / Optional location, at most 60 characters", required = false) String location,
            @ToolParam(description = "最多返回条数 / Maximum returned items, 1–20; default 10", required = false) Integer limit) {
        String q = required(query, "query", 100);
        String s = optional(site, "site", 60);
        String l = optional(location, "location", 60);
        String n = nature == null ? "all" : optional(nature, "nature", 10).toLowerCase(Locale.ROOT);
        if (!Set.of("campus", "intern", "social", "all").contains(n)) throw invalid("nature must be campus, intern, social or all");
        int count = limit == null ? 10 : limit;
        if (count < 1 || count > 20) throw invalid("limit must be between 1 and 20");
        ObjectNode result = jobs.search(q, s, n, l).deepCopy();
        var source = result.path("items");
        var items = result.putArray("items");
        // Preserve sources/degradation metadata while bounding the external response.
        for (int i = 0; i < Math.min(count, source.size()); i++) items.add(searchItem(source.get(i)));
        result.put("limit", count);
        result.put("truncated", source.size() > count || result.path("meta").path("truncated").asBoolean());
        return result;
    }

    private ObjectNode searchItem(JsonNode source) {
        var item = json.createObjectNode();
        for (String field : new String[]{"id", "site", "nature", "source", "snapshotDate"})
            if (source.has(field)) item.put(field, com.offerpilot.common.text.TextBounds.truncate(source.path(field).asText(), 80));
        for (String field : new String[]{"title", "company", "locations"})
            item.put(field, com.offerpilot.common.text.TextBounds.truncate(source.path(field).asText(), 200));
        String url = source.path("url").asText();
        item.put("url", url.length() <= 2048 ? url : "");
        String text = (source.path("description").asText() + "\n" + source.path("requirement").asText())
                .replaceAll("[\\s\\p{Z}]+", " ").strip();
        boolean truncated = text.length() > 240;
        item.put("excerpt", truncated ? com.offerpilot.common.text.TextBounds.truncate(text, 239) + "…" : text);
        item.put("excerptTruncated", truncated);
        return item;
    }

    @Tool(name = "jobs.match", description = "只读岗位匹配：逐项评估JD技能，分数由代码计算，不保存历史。Read-only match of supplied resume text against supplied job description. Returns score, summary and MET/PARTIAL/MISSING requirements with verified text evidence. Never saves match history or accesses private resumes.")
    public ObjectNode matchJob(
            @ToolParam(description = "必填简历纯文本 / Required resume plain text, 1–50000 characters") String resumeText,
            @ToolParam(description = "必填岗位描述 / Required job description, 1–20000 characters") String jobDescription) {
        return matchValidated(required(resumeText, "resumeText", 50000), required(jobDescription, "jobDescription", 20000));
    }

    private ObjectNode matchValidated(String resume, String jd) {
        JsonNode report = matcher.match(json.createObjectNode().put("description", jd), resumeSource(resume));
        var result = json.createObjectNode();
        for (String field : new String[]{"score", "summary", "requirements"}) result.set(field, report.path(field).deepCopy());
        return result;
    }

    private ObjectNode resumeSource(String text) {
        return json.createObjectNode().put("rawText", text);
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank()) throw invalid(field + " is required and must not be blank");
        return optional(value, field, max);
    }

    private static String optional(String value, String field, int max) {
        if (value == null) return "";
        if (value.length() > max) throw invalid(field + " must not exceed " + max + " characters");
        return value.strip();
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }
}
