package com.offerpilot.career.application;

import com.fasterxml.jackson.databind.*;
import com.offerpilot.common.error.*;
import com.offerpilot.resume.application.*;
import com.offerpilot.resume.github.GitHubEvidenceService;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class SkillGapService {
    public record Evidence(String type, String id, String label, String excerpt, String fetchedAt) {}
    public record Skill(String name, String level, List<Evidence> sources) {}
    public record Report(String targetType, String targetId, String targetTitle, String resumeVersionId,
                         List<Skill> skills, String notice) {}
    private final CareerProfileService profiles;
    private final OccupationRecommender occupations;
    private final ResumeService resumes;
    private final ResumeMatchSource resumeSource;
    private final GitHubEvidenceService github;
    private final SkillGapJobSource jobs;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public SkillGapService(CareerProfileService profiles, OccupationRecommender occupations, ResumeService resumes,
                           ResumeMatchSource resumeSource, GitHubEvidenceService github, SkillGapJobSource jobs,
                           JdbcTemplate jdbc, ObjectMapper json) {
        this.profiles = profiles; this.occupations = occupations; this.resumes = resumes; this.resumeSource = resumeSource;
        this.github = github; this.jobs = jobs; this.jdbc = jdbc; this.json = json;
    }
    public Report analyze(String owner, String occupationId, String jobId, String resumeId, String versionId) {
        for (String id : Arrays.asList(occupationId, jobId, resumeId, versionId))
            if (id != null && (id.isBlank() || id.length() > 100)) throw invalid("选择标识无效");
        if (occupationId != null && jobId != null) throw invalid("请选择一个目标岗位或职业");
        if ((resumeId == null) != (versionId == null)) throw invalid("简历和版本必须同时选择");
        var profile = profiles.get(owner);
        String type, targetId, title;
        var required = new LinkedHashMap<String, Evidence>();
        if (jobId != null) {
            var job = jobs.read(owner, jobId); type = "JOB"; targetId = job.id(); title = job.title();
            job.skills().forEach(s -> required.putIfAbsent(s, new Evidence("JOB", job.id(), "岗位技能标签", s, null)));
            // Bounded lexical extraction; no model may invent an absent requirement.
            var vocabulary = new LinkedHashSet<>(List.of("Java", "JavaScript", "TypeScript", "Python", "Go", "C++", "C#", "SQL",
                    "Rust", "Vue", "React", "Spring Boot", "Spring", "Redis", "MySQL", "PostgreSQL", "Docker", "Kubernetes", "Git", "Linux"));
            jdbc.queryForList("SELECT core_skills::text FROM occupations", String.class).forEach(s -> names(parse(s)).forEach(vocabulary::add));
            for (String skill : vocabulary) if (required.size() < 100 && mentions(job.description(), skill))
                required.putIfAbsent(skill, new Evidence("JOB", job.id(), "岗位要求原文", excerpt(job.description(), skill), null));
        } else {
            var occupation = occupations.find(occupationId, occupationId == null ? profile.target() : null);
            type = "OCCUPATION"; targetId = occupation.id(); title = occupation.name();
            names(parse(jdbc.queryForObject("SELECT core_skills::text FROM occupations WHERE id=?", String.class, targetId)))
                    .forEach(s -> required.putIfAbsent(s, new Evidence("OCCUPATION", occupation.id(), "职业核心技能 · " + title, s, null)));
        }
        JsonNode resume = json.createObjectNode(); String selectedVersion = null;
        if (resumeId == null) {
            var latest = resumes.latestReady(owner);
            if (latest != null) { resumeId = latest.id(); versionId = latest.currentVersionId(); }
        }
        if (resumeId != null) { resume = resumeSource.snapshot(owner, resumeId, versionId).structured(); selectedVersion = versionId; }
        var gh = github.get(owner);
        var skills = compare(required, profile.skills(), resume, selectedVersion, gh);
        String notice = "等级表示材料证据覆盖，不是技能测评或熟练度认证：项目技术列出，或自述与有本人提交的非fork语言实证相互印证=已具备；仅自述/仓库语言=部分；无匹配材料=缺失。使用词面与有限别名匹配，未提及不代表不会。";
        if (jobId != null) notice += " 岗位技能来自已存标签与有限词表的原文命中，不保证覆盖完整JD，也未区分必需/加分要求。";
        if (selectedVersion == null) notice += " 暂无可用简历。";
        if (gh.snapshot() == null) notice += " 暂无 GitHub 实证。";
        else if (gh.stale()) notice += " GitHub 缓存已过期或最近抓取失败，仅展示历史依据，不用于提升等级。";
        if (skills.isEmpty()) notice += " 目标尚无可识别技能，未生成差距。";
        return new Report(type, targetId, title, selectedVersion, skills, notice);
    }
    static List<Skill> compare(Map<String, Evidence> required, String profile, JsonNode resume, String version,
                               GitHubEvidenceService.View gh) {
        var result = new ArrayList<Skill>(); var seen = new HashSet<String>();
        for (var entry : required.entrySet()) {
            String name = entry.getKey(); if (!seen.add(normalize(name))) continue;
            var sources = new ArrayList<Evidence>(); sources.add(entry.getValue());
            boolean self = false, project = false, active = false;
            if (mentions(profile, name)) {
                self = true; sources.add(new Evidence("PROFILE", null, "画像技能自述", excerpt(profile, name), null));
            }
            for (var group : resume.path("skills")) for (var item : group.path("items")) if (mentions(item.asText(), name)) {
                self = true; sources.add(new Evidence("RESUME", version, "简历技能", item.asText(), null));
            }
            for (var item : resume.path("projects")) for (var tech : item.path("tech")) if (mentions(tech.asText(), name)) {
                project = true; sources.add(new Evidence("RESUME", version, "简历项目 · " + item.path("name").asText(), tech.asText(), null));
            }
            if (gh.snapshot() != null) for (var repo : gh.snapshot().repositories())
                if (repo.languages().keySet().stream().anyMatch(s -> normalize(s).equals(normalize(name)))) {
                    sources.add(new Evidence("GITHUB", repo.url(), "GitHub · " + repo.name() + (gh.stale() ? "（历史）" : ""),
                            name + "：" + repo.languages().entrySet().stream().filter(e -> normalize(e.getKey()).equals(normalize(name))).mapToLong(Map.Entry::getValue).sum()
                                    + "字节；本人近90天提交" + (repo.commitsTruncated() ? "≥" : "") + repo.commits90d()
                                    + (repo.fork() ? "；fork仓库" : "；语言不证明提交使用该语言"), gh.fetchedAt().toString()));
                    if (!gh.stale() && !repo.fork() && repo.commits90d() > 0) active = true;
                }
            boolean freshGitHub = !gh.stale() && sources.stream().anyMatch(s -> s.type().equals("GITHUB"));
            String level = project || self && active ? "POSSESSED" : self || freshGitHub ? "PARTIAL" : "MISSING";
            if (level.equals("MISSING")) sources.add(new Evidence("ABSENCE", null, "未找到对应技能材料", "未提及不代表没有能力", null));
            result.add(new Skill(name, level, List.copyOf(sources)));
        }
        return List.copyOf(result);
    }
    static boolean mentions(String text, String skill) {
        if (text == null || text.isBlank()) return false;
        String normalized = normalize(text), key = normalize(skill);
        if (key.isBlank()) return false;
        return Pattern.compile("(?<![a-z0-9+#])" + Pattern.quote(key) + "(?![a-z0-9+#])").matcher(normalized).find();
    }
    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " ")
                .replaceAll("\\bjavascript\\b|\\bjs\\b", "javascript")
                .replaceAll("\\btypescript\\b|\\bts\\b", "typescript")
                .replaceAll("\\bspringboot\\b|\\bspring boot\\b", "spring boot")
                .replaceAll("\\bvue3\\b|\\bvue\\.js\\b", "vue")
                .replaceAll("\\breact\\.js\\b", "react");
    }
    private static String excerpt(String text, String skill) {
        // Keep original evidence bounded; normalized aliases never replace the displayed source.
        int offset = text.toLowerCase(Locale.ROOT).indexOf(skill.toLowerCase(Locale.ROOT));
        int start = Math.max(0, offset - 60);
        return text.substring(start, Math.min(text.length(), start + 300));
    }
    private List<String> names(JsonNode array) {
        var names = new ArrayList<String>();
        if (array != null && array.isArray()) array.forEach(n -> {
            String name = n.isTextual() ? n.asText() : n.path("name").asText();
            if (!name.isBlank() && name.length() <= 100 && names.size() < 100) names.add(name);
        });
        return names;
    }
    private JsonNode parse(String value) { try { return json.readTree(value); } catch (Exception ex) { throw new IllegalStateException("Invalid occupation skills"); } }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.VALIDATION_FAILED, message); }
}
