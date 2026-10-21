package com.offerpilot.career.application;

import com.fasterxml.jackson.databind.*;
import com.offerpilot.common.error.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.*;
import java.sql.ResultSet;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class InterviewPlanUpdatesTest {
    final ObjectMapper json=new ObjectMapper();
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final InterviewLearningSource source=mock(InterviewLearningSource.class);
    final InterviewLearningPlans plans=mock(InterviewLearningPlans.class);
    final CoachPlanForChat coach=mock(CoachPlanForChat.class);
    final InterviewPlanUpdates updates=new InterviewPlanUpdates(jdbc,json,source,plans,coach);
    final InterviewLearningSource.Report report=new InterviewLearningSource.Report("r2","后端",List.of(new InterviewLearningSource.Topic("事务边界","报告缺少回滚依据",2)),"job1");
    final CoachPlanForChat.Item old=new CoachPlanForChat.Item("old","已完成旧任务",1,List.of("旧来源"),null,true);
    CoachPlanForChat.RoadmapPayload roadmap=new CoachPlanForChat.RoadmapPayload(List.of(new CoachPlanForChat.Week(1,"原主题",List.of(old))),List.of(new CoachPlanForChat.Milestone("保留里程碑","第4周末")));
    String history="{}";
    @BeforeEach void fixture() throws Exception {
        var rs=mock(ResultSet.class);
        when(rs.getString("id")).thenReturn("p1"); when(rs.getString("report_id")).thenReturn("r1");
        when(rs.getString("start_date")).thenReturn("2026-10-21"); when(rs.getString("target_role")).thenReturn("后端"); when(rs.getString("target_job_id")).thenReturn("job1");
        when(rs.getString("roadmap")).thenAnswer(c -> json.writeValueAsString(roadmap));
        when(rs.getString("applied_updates")).thenAnswer(c -> history);
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<InterviewPlanUpdates.Stored>>any(),eq("alice")))
                .thenAnswer(c -> List.of(c.<RowMapper<InterviewPlanUpdates.Stored>>getArgument(1).mapRow(rs,0)));
        when(source.read("alice","r2")).thenReturn(report);
        var addition=new CoachPlanForChat.Item("r2-learning-0","事务边界",2,List.of("报告缺少回滚依据"),null,false);
        when(coach.fromInterview(report)).thenReturn(new CoachPlanForChat.RoadmapPayload(List.of(new CoachPlanForChat.Week(1,"新主题",List.of(addition),List.of(),
                List.of(new CoachPlanForChat.Evidence("INTERVIEW","r2","面试报告","报告短板")))),List.of()));
        when(jdbc.update(anyString(),any(),any(),eq("p1"),eq("alice"))).thenAnswer(c -> {
            roadmap=json.readValue((String)c.getArgument(1),CoachPlanForChat.RoadmapPayload.class);
            history=c.getArgument(2); return 1;
        });
    }
    @Test void previewIsReadOnlyConfirmationMergesAndRetryDoesNotWriteTwice() {
        var suggestion=updates.preview("alice","r2").get(0);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        assertThat(suggestion.additions().get(0).items().get(0).done()).isFalse();
        var confirmed=updates.confirm("alice","r2","p1",suggestion.fingerprint());
        assertThat(confirmed.roadmap().weeks().get(0).items()).hasSize(2).contains(old);
        assertThat(confirmed.roadmap().milestones()).hasSize(1);
        assertThat(confirmed.roadmap().weeks().get(0).items().get(1).done()).isFalse();
        assertThat(updates.confirm("alice","r2","p1",suggestion.fingerprint())).isEqualTo(confirmed);
        assertThat(updates.preview("alice","r2")).isEmpty();
        verify(jdbc,times(1)).update(contains("SET roadmap="),any(),any(),eq("p1"),eq("alice"));
        verify(jdbc,never()).update(contains("growth_task_completions"),any(Object[].class));
    }
    @Test void rejectsEditedFingerprintsStalePlanAndStaleReportWithoutWrites() {
        var fingerprint=updates.preview("alice","r2").get(0).fingerprint();
        assertThatThrownBy(() -> updates.confirm("alice","r2","p1","0".repeat(64))).isInstanceOf(BusinessException.class);
        roadmap=new CoachPlanForChat.RoadmapPayload(List.of(),List.of());
        assertThatThrownBy(() -> updates.confirm("alice","r2","p1",fingerprint)).isInstanceOf(BusinessException.class);
        var latest=updates.preview("alice","r2").get(0).fingerprint();
        when(source.read("alice","r2")).thenReturn(new InterviewLearningSource.Report("r2","后端",List.of(),"job1"));
        assertThatThrownBy(() -> updates.confirm("alice","r2","p1",latest)).isInstanceOf(BusinessException.class);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void confirmationBindsTheConcreteProposedTasks() {
        var fingerprint=updates.preview("alice","r2").get(0).fingerprint();
        when(coach.fromInterview(report)).thenReturn(new CoachPlanForChat.RoadmapPayload(
                List.of(new CoachPlanForChat.Week(1,"改变的建议",List.of(new CoachPlanForChat.Item("new","不同任务",3,List.of("不同依据"),null,false)))),List.of()));
        assertThatThrownBy(() -> updates.confirm("alice","r2","p1",fingerprint)).isInstanceOf(BusinessException.class);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void enforcesOwnerCompletionNoWeaknessAndExactTarget() {
        when(source.read("bob","r2")).thenThrow(new BusinessException(ErrorCode.INTERVIEW_NOT_FOUND));
        assertThatThrownBy(() -> updates.preview("bob","r2")).isInstanceOf(BusinessException.class);
        when(source.read("alice","r2")).thenReturn(new InterviewLearningSource.Report("r2","后端",report.topics(),"job2"));
        assertThat(updates.preview("alice","r2")).isEmpty();
        assertThat(InterviewPlanUpdates.sameTarget("后端",null,report)).isFalse();
        assertThat(InterviewPlanUpdates.sameTarget("后端",null,new InterviewLearningSource.Report("r","后端",report.topics()))).isTrue();
        assertThat(InterviewPlanUpdates.sameTarget("后端",null,new InterviewLearningSource.Report("r","Java后端",report.topics()))).isFalse();
        when(source.read("alice","r2")).thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED,"请先完成面试"));
        assertThatThrownBy(() -> updates.preview("alice","r2")).isInstanceOf(BusinessException.class);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}
