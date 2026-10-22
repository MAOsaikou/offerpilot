package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.id.IdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * JB-06: import a job the search sources do not cover. Parsing returns a draft only; nothing is stored
 * until the user reviews and saves it. Saved jobs are private (jobs.user_id) and then behave like any
 * other job: match report, board, rewrite and interview all work by job id.
 */
@Service
public class JobImportService {
    public static final String SITE = "imported";
    public static final String SOURCE = "USER_IMPORT";

    /** securityNotices: SC-01 labels of suspected injected instructions found in the imported text (shown, never executed). */
    public record ParseResult(JobImportParser.Draft draft, String url, String sourceKind, List<String> securityNotices, List<String> fileIds) {
        public ParseResult(JobImportParser.Draft draft, String url, String sourceKind, List<String> securityNotices) {
            this(draft,url,sourceKind,securityNotices,List.of());
        }
    }
    private List<String> notices(String owner, String text) {
        return com.offerpilot.llm.quota.LlmUserContext.asUser(owner, () -> gateway.detectInjection(text)).stream()
                .map(com.offerpilot.llm.safety.PromptInjectionDetector::label).toList();
    }
    public record Fields(String title, String company, String locations, String department, String nature,
                         String description, String requirement, String url, List<String> skills) {}

    private final com.offerpilot.llm.ModelGateway gateway;
    private final JobImportParser parser;
    private final JobPageFetcher fetcher;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JobService jobs;

    public JobImportService(JobImportParser parser, JobPageFetcher fetcher, JdbcTemplate jdbc, ObjectMapper json, JobService jobs, com.offerpilot.llm.ModelGateway gateway) {
        this.gateway = gateway;
        this.parser = parser;
        this.fetcher = fetcher;
        this.jdbc = jdbc;
        this.json = json;
        this.jobs = jobs;
    }

    /** Exactly one of text / url. The model call happens outside any transaction. */
    public ParseResult parse(String owner, String text, String url) {
        boolean hasText = text != null && !text.isBlank(), hasUrl = url != null && !url.isBlank();
        if (hasText == hasUrl) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请粘贴岗位描述文本，或填写岗位网址（二选一）。");
        if (hasText) {
            if (text.strip().codePointCount(0, text.strip().length()) < 30)
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "岗位描述太短，请粘贴完整的职责与要求。");
            return new ParseResult(parser.parse(owner, text, null), "", "text", notices(owner, text));
        }
        var page = fetcher.fetch(url);
        return new ParseResult(parser.parse(owner, page.text(), page.title()), page.url(), "url", notices(owner, page.text()));
    }

    /** OCR transcript remains untrusted input; use the same extraction, grounding and SC-01 notices. */
    public ParseResult parseTranscript(String owner, String transcript) {
        return new ParseResult(parser.parse(owner, transcript, null), "", "screenshot", notices(owner, transcript));
    }

    public JsonNode save(String owner, Fields f) {
        String title = required(f.title(), "岗位名称", 100);
        String description = bounded(f.description(), "岗位职责", 30000), requirement = bounded(f.requirement(), "任职要求", 30000);
        if (description.isBlank() && requirement.isBlank())
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "职责和要求至少填写一项，匹配分析需要依据。");
        String nature = f.nature() == null ? "campus" : f.nature();
        if (!Set.of("campus", "intern", "social").contains(nature)) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "招聘类型无效。");
        String url = f.url() == null ? "" : f.url().strip();
        if (!url.isEmpty()) {
            try {
                var uri = java.net.URI.create(url);
                if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
            } catch (IllegalArgumentException ex) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "原岗位链接必须是 http/https 地址。");
            }
            if (url.length() > 2000) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "原岗位链接过长。");
        }
        var data = json.createObjectNode();
        data.put("site", SITE).put("company", bounded(f.company(), "公司", 100)).put("title", title)
                .put("locations", bounded(f.locations(), "城市", 100)).put("category", "")
                .put("department", bounded(f.department(), "部门", 100)).put("updatedAt", "")
                .put("description", description).put("requirement", requirement)
                .put("nature", nature).put("url", url).put("source", SOURCE).put("importedAt", Instant.now().toString());
        var skills = data.putArray("skills");
        if (f.skills() != null) f.skills().stream().filter(s -> s != null && !s.isBlank() && s.length() <= 40).distinct().limit(12).forEach(skills::add);
        // Same owner + same content = same job, so re-importing updates instead of duplicating.
        String externalId = sha256(owner + "\n" + data.path("company").asText() + "\n" + title + "\n" + description + "\n" + requirement).substring(0, 32);
        data.put("externalId", externalId);
        String id = jdbc.queryForObject("""
                INSERT INTO jobs(id,site,external_id,nature,payload,user_id,detail_fetched_at) VALUES (?,?,?,?,?::jsonb,?,now())
                ON CONFLICT(site,external_id,nature) DO UPDATE SET payload=EXCLUDED.payload,updated_at=now()
                WHERE jobs.user_id=EXCLUDED.user_id RETURNING id
                """, String.class, IdGenerator.next("job"), SITE, externalId, nature, data.toString(), owner);
        return jobs.get(owner, id);
    }

    public List<JsonNode> list(String owner) {
        return jdbc.query("SELECT id,payload,detail_fetched_at FROM jobs WHERE user_id=? ORDER BY created_at DESC LIMIT 50",
                (rs, n) -> jobs.view(rs.getString("id"), rs.getString("payload"), rs.getTimestamp("detail_fetched_at")), owner);
    }

    private static String required(String value, String label, int max) {
        String v = bounded(value, label, max);
        if (v.isBlank()) throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + "不能为空。");
        return v;
    }

    private static String bounded(String value, String label, int max) {
        String v = value == null ? "" : value.strip();
        if (v.codePointCount(0, v.length()) > max) throw new BusinessException(ErrorCode.VALIDATION_FAILED, label + "最多 " + max + " 字。");
        return v;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
