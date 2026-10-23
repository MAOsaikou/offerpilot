package com.offerpilot.career.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.resume.application.ResumeMatchSource;
import com.offerpilot.resume.application.ResumeService;
import com.offerpilot.resume.github.GitHubEvidenceService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SkillGapServiceTest {
    static final String LANGUAGES = "Java / Go / Python 等后端语言";
    static final String DATABASE = "关系型数据库与 SQL 调优";
    static final String MIDDLEWARE = "缓存、消息队列等中间件";
    final ObjectMapper json = new ObjectMapper();
    Map<String, SkillGapService.Evidence> required(String... names) {
        var result = new LinkedHashMap<String, SkillGapService.Evidence>();
        for (String name : names) result.put(name,new SkillGapService.Evidence("OCCUPATION","occ","职业技能",name,null));
        return result;
    }
    GitHubEvidenceService.View github(boolean stale, boolean fork, int commits) {
        return github(stale, fork, commits, Map.of("Java",100L,"Python",50L));
    }
    GitHubEvidenceService.View github(boolean stale, boolean fork, int commits, Map<String, Long> languages) {
        var now = Instant.now();
        var repo = new GitHubEvidenceService.Project("fixture/project","https://github.com/fixture/project",fork,100,languages,commits,false,List.of());
        return new GitHubEvidenceService.View("fixture",false,"READY","",now,now.plusSeconds(100),stale,
                new GitHubEvidenceService.Snapshot("snapshot",now.minusSeconds(90*86400),false,List.of(repo),repo.languages(),100,commits));
    }
    GitHubEvidenceService.View noGithub() {
        return new GitHubEvidenceService.View("",false,"NOT_CONFIGURED","",null,null,false,null);
    }
    @Test void combinesResumeProfileAndGithubWithPerSkillSourceAttribution() throws Exception {
        var resume = json.readTree("{\"skills\":[{\"items\":[\"Java\"]}],\"projects\":[{\"name\":\"Coursework\",\"tech\":[\"TypeScript\"]}]}");
        var result = SkillGapService.compare(required("Java","TypeScript","Python","Docker","SQL"),"Docker",resume,"version-1",github(false,false,3));
        assertThat(result).extracting(SkillGapService.Skill::level).containsExactly("POSSESSED","POSSESSED","PARTIAL","PARTIAL","MISSING");
        assertThat(result.get(0).sources()).extracting(SkillGapService.Evidence::type).containsExactly("OCCUPATION","RESUME","GITHUB");
        assertThat(result.get(1).sources().get(1).id()).isEqualTo("version-1");
        assertThat(result.get(4).sources()).extracting(SkillGapService.Evidence::type).contains("ABSENCE");
    }
    @Test void staleForkOrNoAuthoredCommitsCannotUpgradeSelfReport() {
        for (var gh : List.of(github(true,false,3),github(false,true,3),github(false,false,0))) {
            var skill = SkillGapService.compare(required("Java"),"Java",json.createObjectNode(),null,gh).get(0);
            assertThat(skill.level()).isEqualTo("PARTIAL");
            assertThat(skill.sources()).extracting(SkillGapService.Evidence::type).contains("PROFILE","GITHUB");
        }
        assertThat(SkillGapService.compare(required("Python"),"",json.createObjectNode(),null,github(true,false,3)).get(0).level()).isEqualTo("MISSING");
    }
    @Test void normalizesLimitedAliasesAndRejectsSubstringSkillInflation() {
        assertThat(SkillGapService.mentions("JavaScript / TypeScript", "Java")).isFalse();
        assertThat(SkillGapService.mentions("Java后端，掌握SQL", "Java")).isTrue();
        assertThat(SkillGapService.mentions("SpringBoot，Vue3，JS", "Spring Boot")).isTrue();
        assertThat(SkillGapService.mentions("JS", "JavaScript")).isTrue();
        assertThat(SkillGapService.mentions("C++", "C")).isFalse();
        assertThat(SkillGapService.mentions("GoLand", "Go")).isFalse();
        assertThat(SkillGapService.mentions("JavaScript / GoLand", LANGUAGES)).isFalse();
        assertThat(SkillGapService.mentions("JS后端、TS开发、Vue.js、React.js", "JavaScript / TypeScript")).isTrue();
        assertThat(SkillGapService.mentions("Spring开发，Boot基础", "Spring Boot")).isFalse();
        assertThat(SkillGapService.mentions("Spring Boot", "springboot")).isTrue();
        for (var pair : Map.of("JS", "JavaScript", "TS", "TypeScript", "Vue3", "Vue", "Vue.js", "Vue", "React.js", "React").entrySet()) {
            assertThat(SkillGapService.mentions(pair.getKey() + "开发", pair.getValue())).as(pair.getKey()).isTrue();
            assertThat(SkillGapService.mentions(pair.getValue(), pair.getKey())).as(pair.getKey()).isTrue();
        }
    }
    @Test void compoundOccupationRowsKeepNamesAndProfileSourcesWithoutInventingSoftSkills() {
        var result = SkillGapService.compare(required(LANGUAGES, DATABASE, MIDDLEWARE, "并发、性能与稳定性治理"),
                "Java, Spring Boot, MySQL", json.createObjectNode(), null, noGithub());
        assertThat(result).extracting(SkillGapService.Skill::name).containsExactly(LANGUAGES, DATABASE, MIDDLEWARE, "并发、性能与稳定性治理");
        assertThat(result).extracting(SkillGapService.Skill::level).containsExactly("PARTIAL", "PARTIAL", "MISSING", "MISSING");
        for (var skill : result.subList(0, 2))
            assertThat(skill.sources()).extracting(SkillGapService.Evidence::type).containsExactly("OCCUPATION", "PROFILE");
    }
    @Test void resumeProjectRedisPossessesCompoundMiddlewareButSkillsListAloneIsPartial() throws Exception {
        var resume = json.readTree("""
                {"skills":[{"items":["Java", "MySQL", "Redis"]}],
                 "projects":[{"name":"Fixture", "tech":["Redis"]}]}
                """);
        var result = SkillGapService.compare(required(LANGUAGES, DATABASE, MIDDLEWARE), "", resume, "version-1", noGithub());
        assertThat(result).extracting(SkillGapService.Skill::level).containsExactly("PARTIAL", "PARTIAL", "POSSESSED");
        assertThat(result.get(2).sources()).extracting(SkillGapService.Evidence::type).containsExactly("OCCUPATION", "RESUME", "RESUME");
        assertThat(result.get(2).sources().get(2)).satisfies(source -> {
            assertThat(source.id()).isEqualTo("version-1");
            assertThat(source.excerpt()).isEqualTo("Redis");
        });
        assertThat(SkillGapService.compare(required(MIDDLEWARE), "", json.readTree("""
                {"skills":[{"items":["Redis"]}]}
                """), "version-1", noGithub()).get(0).level()).isEqualTo("PARTIAL");
    }
    @Test void splitsRequiredAtomsOnAllSupportedSeparatorsAndNormalizesUnicode() {
        for (String separator : List.of("/", "、", ",", "，", "与", "及", "等", " ", "\t", "\u3000"))
            assertThat(SkillGapService.mentions("Java后端", "Docker" + separator + "Ｊａｖａ")).as(separator).isTrue();
        assertThat(SkillGapService.mentions("SPRING\u3000BOOT、ＲＥＤＩＳ", "Spring Boot / Docker")).isTrue();
    }
    @Test void expandsExplicitCategoryAliasesInBothDirectionsWithoutCrossVendorOrMongoInflation() {
        for (String database : List.of("MySQL", "Postgres", "PostgreSQL", "MariaDB")) {
            assertThat(SkillGapService.mentions(database, DATABASE)).as(database).isTrue();
            assertThat(SkillGapService.mentions("SQL", database)).as(database).isTrue();
            assertThat(SkillGapService.mentions("关系型数据库", database)).as(database).isTrue();
        }
        for (String queue : List.of("Kafka", "RabbitMQ", "RocketMQ", "Pulsar")) {
            assertThat(SkillGapService.mentions(queue, MIDDLEWARE)).as(queue).isTrue();
            assertThat(SkillGapService.mentions("消息队列", queue)).as(queue).isTrue();
        }
        assertThat(SkillGapService.mentions("缓存", "Redis")).isTrue();
        assertThat(SkillGapService.mentions("PostgreSQL", "MySQL")).isFalse();
        assertThat(SkillGapService.mentions("SQLServer / MySQLWorkbench", DATABASE)).isFalse();
        assertThat(SkillGapService.mentions("MongoDB", DATABASE)).isFalse();
        assertThat(SkillGapService.mentions("非关系型数据库", DATABASE)).isFalse();
        assertThat(SkillGapService.mentions("MongoDB", "非关系型数据库 / 文档库")).isTrue();
        assertThat(SkillGapService.mentions("文档库", "MongoDB")).isTrue();
        assertThat(SkillGapService.mentions("MongoDB", MIDDLEWARE)).isFalse();
    }
    @Test void ignoresEmptyAndGenericFragments() {
        for (String skill : Arrays.asList(null, "", " / 、 与 及 等 ", "相关、能力、基础、进阶、治理、后端语言、中间件、调优"))
            assertThat(SkillGapService.mentions("相关能力基础进阶治理后端语言中间件调优", skill)).as(skill).isFalse();
        assertThat(SkillGapService.mentions(null, LANGUAGES)).isFalse();
    }
    @Test void compoundGithubLanguageUsesAtomicKeysAndOnlyCountsMatchingLanguageBytes() {
        var gh = github(false, false, 3, Map.of("Java", 100L, "JavaScript", 200L));
        var skill = SkillGapService.compare(required(LANGUAGES), "", json.createObjectNode(), null, gh).get(0);
        assertThat(skill.level()).isEqualTo("PARTIAL");
        assertThat(skill.sources()).extracting(SkillGapService.Evidence::type).containsExactly("OCCUPATION", "GITHUB");
        assertThat(skill.sources().get(1).excerpt()).contains("100字节").doesNotContain("300字节");
        assertThat(SkillGapService.compare(required(LANGUAGES), "Java", json.createObjectNode(), null, gh).get(0).level()).isEqualTo("POSSESSED");
        assertThat(SkillGapService.compare(required(LANGUAGES, "C"), "", json.createObjectNode(), null,
                github(false, false, 3, Map.of("JavaScript", 1L, "GoLand", 1L, "C++", 1L))))
                .extracting(SkillGapService.Skill::level).containsExactly("MISSING", "MISSING");
    }
    @Test void compoundGithubPreservesStaleForkAndCommitRules() {
        for (var gh : List.of(github(true, false, 3), github(false, true, 3), github(false, false, 0))) {
            assertThat(SkillGapService.compare(required(LANGUAGES), "Java", json.createObjectNode(), null, gh).get(0).level()).isEqualTo("PARTIAL");
            assertThat(SkillGapService.compare(required(LANGUAGES), "", json.createObjectNode(), null, gh).get(0).level())
                    .isEqualTo(gh.stale() ? "MISSING" : "PARTIAL");
        }
    }
    @Test void jobKeepsTaggedSkillsAndExtractsCompoundOccupationVocabularyFromAtomicJdText() {
        var profiles = mock(CareerProfileService.class); var occupations = mock(OccupationRecommender.class);
        var resumes = mock(ResumeService.class); var resumeSource = mock(ResumeMatchSource.class);
        var github = mock(GitHubEvidenceService.class); var jobs = mock(SkillGapJobSource.class); var jdbc = mock(JdbcTemplate.class);
        when(profiles.get("owner")).thenReturn(new CareerProfileService.Profile("", "", "Java, MySQL, Redis", List.of(), List.of(), ""));
        when(github.get("owner")).thenReturn(noGithub());
        when(jobs.read("owner", "job")).thenReturn(new SkillGapJobSource.Job("job", "Fixture", "要求Java后端，掌握MySQL、Redis", List.of("Spring Boot")));
        when(jdbc.queryForList("SELECT core_skills::text FROM occupations", String.class))
                .thenReturn(List.of("[\"" + LANGUAGES + "\",\"" + DATABASE + "\",\"" + MIDDLEWARE + "\"]"));
        var report = new SkillGapService(profiles, occupations, resumes, resumeSource, github, jobs, jdbc, json)
                .analyze("owner", null, "job", null, null);
        assertThat(report.targetType()).isEqualTo("JOB");
        assertThat(report.skills()).extracting(SkillGapService.Skill::name).contains("Spring Boot", "Java", "MySQL", "Redis", LANGUAGES, DATABASE, MIDDLEWARE)
                .doesNotContain("JavaScript", "Go");
        for (var skill : report.skills()) {
            assertThat(skill.level()).isEqualTo(skill.name().equals("Spring Boot") ? "MISSING" : "PARTIAL");
            assertThat(skill.sources().get(0).type()).isEqualTo("JOB");
        }
        verifyNoInteractions(occupations, resumeSource);
    }
    @Test void noResumeOrGithubStillProducesHonestMissingAndPartialResults() {
        var empty = new GitHubEvidenceService.View("",false,"NOT_CONFIGURED","",null,null,false,null);
        assertThat(SkillGapService.compare(required("Java","SQL"),"Java",json.createObjectNode(),null,empty))
                .extracting(SkillGapService.Skill::level).containsExactly("PARTIAL","MISSING");
        assertThat(SkillGapService.compare(required(),"Java",json.createObjectNode(),null,empty)).isEmpty();
    }
}
