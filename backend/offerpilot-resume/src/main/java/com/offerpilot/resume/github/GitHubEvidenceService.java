package com.offerpilot.resume.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.common.error.*;
import com.offerpilot.github.*;
import com.offerpilot.resume.application.ResumeService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;

@Service
public class GitHubEvidenceService {
    public record Project(String name, String url, boolean fork, long stars, Map<String, Long> languages,
                          int commits90d, boolean commitsTruncated, List<String> highlights) {}
    public record Snapshot(String id, Instant since, boolean repositoriesTruncated, List<Project> repositories,
                           Map<String, Long> languages, long stars, int commits90d) {}
    public record View(String username, boolean tokenConfigured, String status, String notice,
                       Instant fetchedAt, Instant expiresAt, boolean stale, Snapshot snapshot) {}
    private record Stored(String username, String encrypted, String revision, String status, String notice,
                          Instant fetchedAt, Snapshot snapshot) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final GitHubClient client;
    private final GitHubTokenCipher cipher;
    private final GitHubHighlights highlights;
    private final ResumeService resumes;
    private final TransactionTemplate tx;
    public GitHubEvidenceService(JdbcTemplate jdbc, ObjectMapper json, GitHubClient client, GitHubTokenCipher cipher,
                                 GitHubHighlights highlights, ResumeService resumes, TransactionTemplate tx) {
        this.jdbc = jdbc; this.json = json; this.client = client; this.cipher = cipher;
        this.highlights = highlights; this.resumes = resumes; this.tx = tx;
    }
    public View get(String owner) { return view(read(owner)); }
    public View save(String owner, String username, String token, boolean removeToken) {
        username = username == null ? "" : username.strip();
        if (!RestGitHubClient.validUsername(username)) throw invalid("请输入有效的公开 GitHub 用户名");
        if (token != null && (!token.matches("[A-Za-z0-9_]{1,255}") || removeToken)) throw invalid("GitHub Token 格式或操作无效");
        // Encrypt before entering JDBC, so no SQL exception can contain the submitted credential.
        String encrypted = token == null ? null : cipher.encrypt(owner, token);
        String name = username;
        return tx.execute(status -> {
            lockOwner(owner);
            var old = read(owner);
            String credential = removeToken ? null : encrypted != null ? encrypted
                    : old != null && old.username().equalsIgnoreCase(name) ? old.encrypted() : null;
            jdbc.update("""
                    INSERT INTO github_evidence(user_id,username,token_ciphertext,revision) VALUES (?,?,?,?)
                    ON CONFLICT(user_id) DO UPDATE SET username=EXCLUDED.username,token_ciphertext=EXCLUDED.token_ciphertext,
                    revision=EXCLUDED.revision,snapshot=NULL,fetched_at=NULL,status='NOT_FETCHED',notice=''
                    """, owner, name, credential, UUID.randomUUID().toString());
            return get(owner);
        });
    }
    public void delete(String owner) {
        tx.executeWithoutResult(status -> { lockOwner(owner); jdbc.update("DELETE FROM github_evidence WHERE user_id=?", owner); });
    }
    public View refresh(String owner, boolean force) {
        var stored = read(owner);
        if (stored == null) throw invalid("请先保存公开 GitHub 用户名");
        var current = view(stored);
        if (!force && current.snapshot() != null && !current.stale()) return current;
        if (jdbc.update("""
                UPDATE github_evidence SET last_attempt_at=now() WHERE user_id=? AND revision=?
                AND (last_attempt_at IS NULL OR last_attempt_at < now()-interval '1 minute')
                """, owner, stored.revision()) == 0)
            throw new BusinessException(ErrorCode.RATE_LIMITED, "GitHub 抓取进行中或过于频繁，请一分钟后重试");
        try {
            String token = stored.encrypted() == null ? null : cipher.decrypt(owner, stored.encrypted());
            var source = client.fetch(stored.username(), token);
            Map<String, List<String>> quotes;
            String state = source.notice().isEmpty() ? "READY" : "STATISTICS_ONLY";
            String notice = scope(token != null) + " " + source.notice();
            try { quotes = source.notice().isEmpty() ? highlights.extract(owner, source) : Map.of(); }
            catch (RuntimeException ex) { quotes = Map.of(); state = "STATISTICS_ONLY"; notice += " README 亮点提炼不可用，已降级为仅统计。"; }
            var projects = new ArrayList<Project>();
            var languages = new TreeMap<String, Long>(); long stars = 0; int commits = 0;
            for (var repo : source.repositories()) {
                projects.add(new Project(repo.name(), repo.url(), repo.fork(), repo.stars(), repo.languages(), repo.commits90d(),
                        repo.commitsTruncated(), quotes.getOrDefault(repo.name(), List.of())));
                repo.languages().forEach((key, value) -> languages.merge(key, value, Long::sum));
                stars += repo.stars(); commits += repo.commits90d();
            }
            var snapshot = new Snapshot(UUID.randomUUID().toString(), source.since(), source.repositoriesTruncated(),
                    List.copyOf(projects), Map.copyOf(languages), stars, commits);
            jdbc.update("UPDATE github_evidence SET snapshot=?::jsonb,fetched_at=now(),status=?,notice=? WHERE user_id=? AND revision=?",
                    encode(snapshot), state, notice, owner, stored.revision());
        } catch (GitHubClient.Failure ex) {
            jdbc.update("UPDATE github_evidence SET status=?,notice=? WHERE user_id=? AND revision=?", ex.status(), ex.getMessage(), owner, stored.revision());
        }
        return get(owner); // A concurrent settings change always wins; late results cannot restore credentials/cache.
    }
    public ResumeService.Resume adopt(String owner, String resumeId, String baseVersionId, String snapshotId, String repository, boolean confirmed) {
        if (!confirmed) throw invalid("请先确认项目原文与个人贡献，再采纳建议");
        for (String value : Arrays.asList(resumeId, baseVersionId, snapshotId, repository))
            if (value == null || value.isBlank() || value.length() > 200) throw invalid("请选择简历当前版本与项目建议");
        return tx.execute(status -> {
            lockOwner(owner);
            var view = get(owner);
            if (view.snapshot() == null || view.stale() || !view.snapshot().id().equals(snapshotId))
                throw invalid("GitHub 实证已变化或过期，请刷新后重新确认");
            var project = view.snapshot().repositories().stream().filter(r -> r.name().equals(repository)).findFirst()
                    .orElseThrow(() -> invalid("项目不存在"));
            if (project.highlights().isEmpty()) throw invalid("此项目没有可采纳的 README 亮点");
            var resume = resumes.get(owner, resumeId);
            if (!"READY".equals(resume.parseStatus()) || resume.currentVersion() == null)
                throw new BusinessException(ErrorCode.RESUME_BUSY);
            if (!Objects.equals(baseVersionId, resume.currentVersionId())) throw new BusinessException(ErrorCode.RESUME_VERSION_CONFLICT);
            ObjectNode data = resume.currentVersion().structured().deepCopy();
            var projects = data.withArray("projects");
            if (projects.size() >= 50) throw invalid("简历项目已达上限，请先整理现有项目");
            // Repeat clicks do not create duplicate projects or versions.
            for (var existing : projects) if (existing.path("name").asText().equals(project.name()))
                throw invalid("简历已有同名项目，请在编辑器中核对和完善");
            var item = projects.addObject().put("name", project.name()).put("role", "").put("start", "").put("end", "");
            item.putArray("tech"); // Repository languages do not establish this user's contribution.
            var bullets = item.putArray("bullets");
            bullets.add("项目公开说明（个人贡献需自行补充）：" + project.url());
            project.highlights().forEach(bullets::add);
            return resumes.save(owner, resumeId, baseVersionId, data);
        });
    }
    private Stored read(String owner) {
        return jdbc.query("SELECT * FROM github_evidence WHERE user_id=?", (rs, n) -> {
            Snapshot snapshot = null;
            try { if (rs.getString("snapshot") != null) snapshot = json.readValue(rs.getString("snapshot"), Snapshot.class); }
            catch (Exception ex) { throw new IllegalStateException("Invalid stored GitHub evidence"); }
            var fetched = rs.getTimestamp("fetched_at");
            return new Stored(rs.getString("username"), rs.getString("token_ciphertext"), rs.getString("revision"),
                    rs.getString("status"), rs.getString("notice"), fetched == null ? null : fetched.toInstant(), snapshot);
        }, owner).stream().findFirst().orElse(null);
    }
    private View view(Stored row) {
        if (row == null) return new View("", false, "NOT_CONFIGURED", "请填写公开 GitHub 用户名，Token 可选。", null, null, false, null);
        var expires = row.fetchedAt() == null ? null : row.fetchedAt().plus(Duration.ofHours(6));
        boolean stale = expires != null && (!expires.isAfter(Instant.now()) || !Set.of("READY", "STATISTICS_ONLY").contains(row.status()));
        return new View(row.username(), row.encrypted() != null, row.status(), row.notice(), row.fetchedAt(), expires, stale, row.snapshot());
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception ex) { throw new IllegalStateException("Cannot encode GitHub evidence"); }
    }
    private void lockOwner(String owner) {
        if (jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE", owner).isEmpty()) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.VALIDATION_FAILED, message); }
    private static String scope(boolean authenticated) { return "统计最近推送的至多" + (authenticated ? 20 : 15) + "个公开自有仓库（含 fork）；语言按字节数，Star 为所统计仓库获星数之和；近90天提交仅默认分支且 author 为该用户名，每仓库最多100条，达到上限标记为下界。仓库归属不证明个人贡献或技能熟练度。"; }
}
