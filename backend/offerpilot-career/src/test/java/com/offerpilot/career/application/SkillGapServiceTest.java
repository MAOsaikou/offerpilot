package com.offerpilot.career.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.resume.github.GitHubEvidenceService;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class SkillGapServiceTest {
    final ObjectMapper json = new ObjectMapper();
    Map<String, SkillGapService.Evidence> required(String... names) {
        var result = new LinkedHashMap<String, SkillGapService.Evidence>();
        for (String name : names) result.put(name,new SkillGapService.Evidence("OCCUPATION","occ","职业技能",name,null));
        return result;
    }
    GitHubEvidenceService.View github(boolean stale, boolean fork, int commits) {
        var now = Instant.now();
        var repo = new GitHubEvidenceService.Project("fixture/project","https://github.com/fixture/project",fork,100,Map.of("Java",100L,"Python",50L),commits,false,List.of());
        return new GitHubEvidenceService.View("fixture",false,"READY","",now,now.plusSeconds(100),stale,
                new GitHubEvidenceService.Snapshot("snapshot",now.minusSeconds(90*86400),false,List.of(repo),repo.languages(),100,commits));
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
    }
    @Test void noResumeOrGithubStillProducesHonestMissingAndPartialResults() {
        var empty = new GitHubEvidenceService.View("",false,"NOT_CONFIGURED","",null,null,false,null);
        assertThat(SkillGapService.compare(required("Java","SQL"),"Java",json.createObjectNode(),null,empty))
                .extracting(SkillGapService.Skill::level).containsExactly("PARTIAL","MISSING");
        assertThat(SkillGapService.compare(required(),"Java",json.createObjectNode(),null,empty)).isEmpty();
    }
}
