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
