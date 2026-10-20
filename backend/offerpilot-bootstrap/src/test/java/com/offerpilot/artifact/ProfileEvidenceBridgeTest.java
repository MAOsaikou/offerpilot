package com.offerpilot.artifact;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.auth.application.UserDirectory;
import com.offerpilot.resume.application.*;
import com.offerpilot.resume.github.GitHubEvidenceService;
import com.offerpilot.interview.application.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class ProfileEvidenceBridgeTest {
    final ResumeService resumes=mock(ResumeService.class);
    final ResumeMatchSource snapshots=mock(ResumeMatchSource.class);
    final GitHubEvidenceService github=mock(GitHubEvidenceService.class);
    final InterviewService interviews=mock(InterviewService.class);
    final ProfileEvidenceBridge source=new ProfileEvidenceBridge(resumes,snapshots,github,interviews,mock(UserDirectory.class));
    @Test void onlyLiteralResumeSkillsAndProjectsBecomeProposalsWithoutContactDetails() throws Exception {
        var resume=mock(ResumeService.Resume.class);when(resume.id()).thenReturn("r");when(resume.currentVersionId()).thenReturn("v");when(resumes.latestReady("alice")).thenReturn(resume);
        var data=new ObjectMapper().readTree("{\"basics\":{\"email\":\"private@example.test\"},\"skills\":[{\"items\":[\"Java\",\"SQL\",\"Java\"]}],\"projects\":[{\"tech\":[\"Docker\"]}]}");
        when(snapshots.snapshot("alice","r","v")).thenReturn(new ResumeMatchSource.Snapshot("r","title","v",1,data));
        when(github.get("alice")).thenReturn(new GitHubEvidenceService.View("",false,"NOT_FETCHED","",null,null,false,null));
        var observations=source.read("alice");assertThat(observations).hasSize(1);assertThat(observations.get(0).skills()).containsExactly("Java","SQL","Docker");assertThat(observations.toString()).doesNotContain("private@example.test");verify(snapshots).snapshot("alice","r","v");
    }
    @Test void staleForkAndNoAuthorCommitGithubAreNeverSkillsEvidence() {
        var active=new GitHubEvidenceService.Project("active","https://github.com/u/active",false,1,Map.of("Python",100L),2,false,List.of());
        var fork=new GitHubEvidenceService.Project("fork","https://github.com/u/fork",true,1,Map.of("Java",200L),3,false,List.of());
        var noCommit=new GitHubEvidenceService.Project("idle","https://github.com/u/idle",false,1,Map.of("Rust",200L),0,false,List.of());
        var snapshot=new GitHubEvidenceService.Snapshot("snapshot",Instant.EPOCH,false,List.of(active,fork,noCommit),Map.of(),0,5);
        when(github.get("alice")).thenReturn(new GitHubEvidenceService.View("u",false,"READY","",Instant.EPOCH,Instant.MAX,false,snapshot));
        var observations=source.read("alice");assertThat(observations).hasSize(1);assertThat(observations.get(0).skills()).containsExactly("Python");assertThat(observations.get(0).facts().toString()).contains("不证明本人使用或掌握");
        when(github.get("alice")).thenReturn(new GitHubEvidenceService.View("u",false,"UNAVAILABLE","",Instant.EPOCH,Instant.EPOCH,true,snapshot));assertThat(source.read("alice")).isEmpty();
    }
    @Test void interviewReportAddsOnlyAttributedObservationsNeverClaimsSkillMastery() {
        when(github.get("alice")).thenReturn(new GitHubEvidenceService.View("",false,"NOT_FETCHED","",null,null,false,null));
        var session=mock(InterviewService.Session.class);when(session.id()).thenReturn("iv");when(session.updatedAt()).thenReturn("time");when(interviews.latestCompleted("alice")).thenReturn(session);
        var report=new InterviewRubric.Report("iv",70,null,List.of(),List.of("表达清楚"),List.of("验证方式不足"),List.of(),List.of(),"基于回答");when(interviews.report("alice","iv")).thenReturn(report);
        var observation=source.read("alice").get(0);assertThat(observation.type()).isEqualTo("INTERVIEW");assertThat(observation.skills()).isEmpty();assertThat(observation.facts()).containsExactly("面试报告优点：表达清楚","面试报告待练习：验证方式不足");
        when(interviews.report("alice","iv")).thenReturn(new InterviewRubric.Report("iv",null,null,List.of(),List.of(),List.of(),List.of(),List.of(),"未评分"));assertThat(source.read("alice")).isEmpty();
    }
}
