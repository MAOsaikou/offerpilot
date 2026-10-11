package com.offerpilot.agent.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.resume.application.*;
import com.offerpilot.common.error.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoachResumeSkillsAdapterTest {
    final ResumeService resumes = mock(ResumeService.class);
    final ResumeMatchSource source = mock(ResumeMatchSource.class);
    final CoachResumeSkillsAdapter adapter = new CoachResumeSkillsAdapter(resumes, source);
    @Test void explicitAndLatestSnapshotsUseOwnerCheckedReaderAndReturnOnlySkillNames() throws Exception {
        var data = new ObjectMapper().readTree("""
                {"basics":{"phone":"private"},"skills":[{"category":"技术","items":["Java","SQL"]}],
                 "projects":[{"name":"secret project","tech":["Java","Vue"],"bullets":["secret text"]}]}
                """);
        when(source.snapshot("alice","rs","rv")).thenReturn(new ResumeMatchSource.Snapshot("rs","title","rv",1,data));
        assertThat(adapter.read("alice","rs","rv").skills()).containsExactly("Java","SQL","Vue");
        verifyNoInteractions(resumes);
        var latest = new ResumeService.Resume("rs","title","READY",100,null,"rv","file",null,null,null);
        when(resumes.latestReady("alice")).thenReturn(latest);
        assertThat(adapter.read("alice",null,null).skills()).containsExactly("Java","SQL","Vue");
        verify(resumes).latestReady("alice");
        verify(source,times(2)).snapshot("alice","rs","rv");
        var matches = mock(com.offerpilot.job.application.JobMatchService.class);
        var interviews = mock(com.offerpilot.interview.application.InterviewService.class);
        var applications = mock(com.offerpilot.board.application.ApplicationService.class);
        var report = new ObjectMapper().readTree("""
                {"id":"match_1","jobId":"job_1","jobSnapshot":{"title":"工程师"},"evaluation":{"dimensions":[
                 {"code":"A","name":"要求覆盖","score":40,"reason":"缺少证据"},
                 {"code":"B","name":"证据强度","score":50,"reason":"引用不足"}]}}
                """);
        when(matches.latestCompleted("alice", "job_1", "工程师")).thenReturn(report);
        when(interviews.latestCompleted("alice")).thenReturn(new com.offerpilot.interview.application.InterviewService.Session(
                "iv_1", "工程师", "项目与经历", 0, true, "now", "now",
                List.of(new com.offerpilot.interview.application.InterviewService.Answer(0, "介绍项目", "我实现了接口，并对请求参数、异常情况和返回值逐一做了校验。")), "job_1"));
        var signals = new CoachEvidenceAdapter(matches, interviews, applications).read("alice", "job_1", null, "工程师");
        assertThat(signals.gaps()).extracting(com.offerpilot.career.application.CoachEvidence.Gap::name)
                .contains("岗位评估：要求覆盖", "岗位评估：证据强度", "面试表现：岗位关联", "面试表现：逻辑结构", "STAR 回答结构")
                .doesNotContain("岗位 A · 要求覆盖", "面试 岗位匹配", "面试 逻辑");
        assertThat(signals.gaps().stream().filter(g -> g.source().type().equals("INTERVIEW")))
                .extracting(g -> g.source().jobId()).containsOnly("job_1");
        verifyNoInteractions(applications);

    }
    @Test void absentAndForeignVersionsNeverExposeOtherOwnersSkillsOrGuessPartialSelection() {
        assertThat(adapter.read("alice",null,null).available()).isFalse();
        assertThat(adapter.read("alice","rs",null).available()).isFalse();
        when(source.snapshot("alice","foreign","rv")).thenThrow(new BusinessException(ErrorCode.RESUME_NOT_FOUND));
        assertThat(adapter.read("alice","foreign","rv").skills()).isEmpty();
        when(source.snapshot("alice","busy","rv")).thenThrow(new BusinessException(ErrorCode.RESUME_BUSY));
        assertThat(adapter.read("alice","busy","rv").available()).isFalse();
        verify(resumes,times(1)).latestReady("alice");
        verify(source,never()).snapshot(eq("alice"),eq("rs"),any());
    }
}
