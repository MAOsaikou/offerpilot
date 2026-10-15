package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import com.offerpilot.llm.quota.LlmUserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JB-06: turn pasted / fetched JD text into a job draft the user reviews before saving. The model only
 * selects and labels; every line of responsibilities and requirements, and every metadata value, must be
 * found in the source text, otherwise it is dropped. Heading-based rules are the offline fallback.
 */
@Component
public class JobImportParser {
    private static final Logger log = LoggerFactory.getLogger(JobImportParser.class);
    static final int MAX_SOURCE = 20000;
    private static final Pattern REQUIREMENT = Pattern.compile(
            "^(?:[#*【\\[]\\s*)?(任职要求|岗位要求|任职资格|职位要求|岗位资格|能力要求|我们希望你|希望你|加分项|Qualifications?|Requirements?|What we.re looking for)\\s*[】\\]]?\\s*[:：]?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DESCRIPTION = Pattern.compile(
            "^(?:[#*【\\[]\\s*)?(岗位职责|工作职责|职位描述|职位职责|工作内容|岗位描述|你将负责|你的职责|Responsibilities|What you.ll do|Job Description)\\s*[】\\]]?\\s*[:：]?\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_LABEL = Pattern.compile("^(?:职位名称|岗位名称|职位|岗位)\\s*[:：]\\s*(.{2,60})$");
    private static final Pattern COMPANY_LABEL = Pattern.compile("^(?:公司名称|公司|企业|招聘单位|用人单位)\\s*[:：]\\s*(.{2,60})$");
    private static final Pattern LOCATION_LABEL = Pattern.compile("^(?:工作地点|工作城市|地点|城市|Base)\\s*[:：]\\s*(.{2,60})$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEPARTMENT_LABEL = Pattern.compile("^(?:所属部门|部门|团队|事业群)\\s*[:：]\\s*(.{2,60})$");
    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*•·●▪]|\\d{1,2}\\s*[.、)）]|[（(]\\d{1,2}[)）])\\s*");

    public record Draft(String title, String company, String locations, String department, String nature,
                        String description, String requirement, List<String> skills, String parser) {}

    private final ObjectProvider<ModelGateway> gateways;
    private final ObjectMapper json;
    private final String prompt;

    public JobImportParser(ObjectProvider<ModelGateway> gateways, ObjectMapper json,
            @Value("classpath:prompts/job/import-parse.st") Resource resource) throws java.io.IOException {
        this.gateways = gateways;
        this.json = json;
        this.prompt = resource.getContentAsString(StandardCharsets.UTF_8);
    }

    public Draft parse(String owner, String source, String titleHint) {
        String text = com.offerpilot.common.text.TextBounds.truncate(source.strip(), MAX_SOURCE);
        Draft rules = rules(text, titleHint);
        var gateway = gateways.getIfAvailable();
        if (gateway != null) {
            try {
                gateway.requireAvailable(ModelTier.FLASH, false);
                List<Message> messages = List.of(new SystemMessage(prompt), new UserMessage(text));
                String output = LlmUserContext.asUser(owner, () -> gateway.stream(ModelTier.FLASH, messages, 0.0)
                        .map(r -> r.getResult() == null || r.getResult().getOutput().getText() == null ? "" : r.getResult().getOutput().getText())
                        .reduce("", String::concat).block(Duration.ofSeconds(60)));
                Draft grounded = ground(output, text, rules);
                if (grounded != null) return grounded;
                log.warn("岗位导入模型输出未通过原文校验，改用规则解析");
            } catch (RuntimeException ex) {
                log.warn("岗位导入模型不可用 ({})，改用规则解析", ex.getClass().getSimpleName());
            }
        }
        return rules;
    }

    /** Keep only model output that can be found in the source; fall back per field to the rule draft. */
    Draft ground(String output, String source, Draft rules) {
        if (output == null) return null;
        JsonNode node;
        try {
            String body = output.strip().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
            node = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body);
        } catch (Exception ex) {
            return null;
        }
        if (node == null || !node.isObject()) return null;
        String haystack = squash(source);
        String title = grounded(node.path("title").asText(""), haystack, 100);
        if (title.isEmpty()) title = rules.title();
        if (title.isEmpty()) return null;
        String description = groundedLines(node.path("description").asText(""), haystack);
        String requirement = groundedLines(node.path("requirement").asText(""), haystack);
        if (description == null) description = rules.description();
        if (requirement == null) requirement = rules.requirement();
        if (description.isBlank() && requirement.isBlank()) return null;
        String nature = node.path("nature").asText("");
        if (!Set.of("campus", "intern", "social").contains(nature)) nature = rules.nature();
        var skills = new ArrayList<String>();
        String lower = source.toLowerCase(Locale.ROOT);
        for (JsonNode skill : node.path("skills")) {
            String s = skill.asText("").strip();
            if (!s.isEmpty() && s.length() <= 40 && lower.contains(s.toLowerCase(Locale.ROOT)) && !skills.contains(s) && skills.size() < 12)
                skills.add(s);
        }
        return new Draft(title, or(grounded(node.path("company").asText(""), haystack, 100), rules.company()),
                or(groundedLocations(node.path("locations").asText(""), haystack), rules.locations()),
                or(grounded(node.path("department").asText(""), haystack, 100), rules.department()),
                nature, description, requirement, skills, "model");
    }

    /** Heading-based split that never invents text. */
    Draft rules(String source, String titleHint) {
        String title = "", firstShort = "", company = "", locations = "", department = "";
        var description = new StringBuilder();
        var requirement = new StringBuilder();
        StringBuilder current = null;
        boolean sawHeading = false;
        for (String raw : source.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            Matcher m;
            if ((m = REQUIREMENT.matcher(line)).matches()) { current = requirement; sawHeading = true; append(current, m.group(2)); continue; }
            if ((m = DESCRIPTION.matcher(line)).matches()) { current = description; sawHeading = true; append(current, m.group(2)); continue; }
            if (title.isEmpty() && (m = TITLE_LABEL.matcher(line)).matches()) { title = m.group(1).strip(); continue; }
            if (company.isEmpty() && (m = COMPANY_LABEL.matcher(line)).matches()) { company = m.group(1).strip(); continue; }
            if (locations.isEmpty() && (m = LOCATION_LABEL.matcher(line)).matches()) { locations = m.group(1).strip(); continue; }
            if (department.isEmpty() && (m = DEPARTMENT_LABEL.matcher(line)).matches()) { department = m.group(1).strip(); continue; }
            if (current != null) append(current, line);
            else if (firstShort.isEmpty() && line.codePointCount(0, line.length()) <= 40 && !line.contains("：") && !line.contains(":")) firstShort = line;
        }
        if (!sawHeading) description.append(source.strip());
        // Page <title> is usually "岗位 - 公司招聘"; prefer it over the first short line, which may be navigation.
        if (title.isEmpty() && titleHint != null) title = titleHint.replaceAll("\\s*[-_|｜].*$", "").strip();
        if (title.isEmpty()) title = firstShort;
        String all = source.toLowerCase(Locale.ROOT);
        String nature = all.contains("实习") || all.contains("intern") ? "intern"
                : all.contains("校招") || all.contains("校园招聘") || all.contains("应届") || all.contains("届毕业") ? "campus"
                : all.contains("社招") || all.contains("社会招聘") ? "social" : "campus";
        return new Draft(clip(title, 100), clip(company, 100), clip(locations, 100), clip(department, 100), nature,
                clip(description.toString().strip(), 30000), clip(requirement.toString().strip(), 30000), List.of(), "rule");
    }

    private static void append(StringBuilder target, String line) {
        if (line == null || line.isBlank()) return;
        if (!target.isEmpty()) target.append('\n');
        target.append(line.strip());
    }

    private static String groundedLines(String value, String haystack) {
        if (value.isBlank()) return null;
        var kept = new ArrayList<String>();
        int total = 0;
        for (String line : value.split("\\R")) {
            String t = line.strip();
            if (t.isEmpty()) continue;
            total++;
            String core = BULLET.matcher(t).replaceFirst("");
            if (core.length() >= 2 && haystack.contains(squash(core))) kept.add(t);
        }
        // A mostly paraphrased section is untrustworthy as a whole; use the rule split instead.
        if (total == 0 || kept.size() * 2 < total) return null;
        return clip(String.join("\n", kept), 30000);
    }

    private static String grounded(String value, String haystack, int max) {
        String v = value.strip();
        return v.isEmpty() || v.length() > max || !haystack.contains(squash(v)) ? "" : v;
    }

    private static String groundedLocations(String value, String haystack) {
        var kept = new ArrayList<String>();
        for (String part : value.split("[、,，/;；\\s]+"))
            if (!part.isBlank() && haystack.contains(squash(part))) kept.add(part.strip());
        return clip(String.join("、", kept), 100);
    }

    static String squash(String s) {
        return s.replaceAll("[\\s\\u00a0\\u3000]+", "").toLowerCase(Locale.ROOT);
    }

    private static String or(String value, String fallback) { return value.isEmpty() ? fallback : value; }

    private static String clip(String s, int max) { return com.offerpilot.common.text.TextBounds.truncate(s, max); }
}
