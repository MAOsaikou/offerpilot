package com.offerpilot.artifact;
import com.offerpilot.interview.application.*;
import com.offerpilot.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class InterviewLearningBridgeTest {
    @Test void unfinishedAndForeignReportsCannotBeUsedAndActualJobIdIsKept() {
        var interviews=mock(InterviewService.class); var bridge=new InterviewLearningBridge(interviews);
        var session=new InterviewService.Session("r","后端","技术",0,false,"now","now",List.of(),"j1");
        when(interviews.getForUpdate("alice","r")).thenReturn(session);
        assertThatThrownBy(() -> bridge.read("alice","r")).isInstanceOf(BusinessException.class);
        verify(interviews,never()).report(anyString(),anyString());
        when(interviews.getForUpdate("bob","r")).thenThrow(new BusinessException(com.offerpilot.common.error.ErrorCode.INTERVIEW_NOT_FOUND));
        assertThatThrownBy(() -> bridge.read("bob","r")).isInstanceOf(BusinessException.class);
        when(interviews.getForUpdate("alice","r")).thenReturn(new InterviewService.Session("r","后端","技术",0,true,"now","now",List.of(),"j1"));
        when(interviews.report("alice","r")).thenReturn(new InterviewRubric.Report("r",null,null,List.of(),List.of(),List.of(),List.of(),
                List.of(new InterviewRubric.Learning("事务边界","已保存报告依据",2)),""));
        var report=bridge.read("alice","r");
        assertThat(report.jobId()).isEqualTo("j1");
        assertThat(report.topics().get(0).reason()).isEqualTo("已保存报告依据");
    }
}
