package com.offerpilot.career.application;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.*;
import com.offerpilot.common.notification.NotificationSink;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.support.*;
import java.sql.ResultSet;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class CareerTargetPlansTest {
    @Test void compareAndSetClaimPreventsDuplicateSearches() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var rs=mock(ResultSet.class);var jobs=mock(TargetJobSearch.class);
        when(rs.getString("revision")).thenReturn("r1");when(rs.getString("target")).thenReturn("开发");when(rs.getString("city")).thenReturn("上海");when(rs.getString("status")).thenReturn("PENDING");
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<CareerTargetPlans.Plan>>any(),eq("alice"))).thenAnswer(c->List.of(c.<RowMapper<CareerTargetPlans.Plan>>getArgument(1).mapRow(rs,0)));
        var service=new CareerTargetPlans(jdbc,new ObjectMapper(),jobs,mock(SkillGapService.class),mock(CoachPlanForChat.class),mock(NotificationSink.class),mock(TransactionTemplate.class));
        service.generate("alice");verifyNoInteractions(jobs);
        assertThatThrownBy(()->service.confirm("alice","old")).isInstanceOf(BusinessException.class);
    }
    @Test void partialFailureKeepsSuccessfulPartsAndLateRevisionCannotNotify() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var rs=mock(ResultSet.class);var jobs=mock(TargetJobSearch.class);var sink=mock(NotificationSink.class);
        var json=new ObjectMapper();var gaps=mock(SkillGapService.class);var coach=mock(CoachPlanForChat.class);var tx=mock(TransactionTemplate.class);
        when(rs.getString("revision")).thenReturn("r1");when(rs.getString("target")).thenReturn("开发");when(rs.getString("city")).thenReturn("上海");
        when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<CareerTargetPlans.Plan>>any(),eq("alice"))).thenAnswer(c->List.of(c.<RowMapper<CareerTargetPlans.Plan>>getArgument(1).mapRow(rs,0)));
        when(jdbc.update(contains("AND (status='PENDING'"),eq("alice"),eq("r1"))).thenReturn(1);
        when(jobs.search("alice","开发","上海")).thenReturn(json.readTree("{\"items\":[]}"));
        when(gaps.analyze("alice",null,null,null,null)).thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED));
        when(coach.plan("alice",null,null,"r1")).thenReturn(new CoachPlanForChat.Result("no invented plan",null,null));
        doAnswer(c->{c.<java.util.function.Consumer<org.springframework.transaction.TransactionStatus>>getArgument(0).accept(mock(org.springframework.transaction.TransactionStatus.class));return null;}).when(tx).executeWithoutResult(any());
        when(jdbc.queryForList(contains("FROM users"),eq(String.class),eq("alice"))).thenReturn(List.of("alice"));
        new CareerTargetPlans(jdbc,json,jobs,gaps,coach,sink,tx).generate("alice");
        verify(jdbc).update(contains("AND revision=? AND status='RUNNING'"),eq("FAILED"),contains("no invented plan"),contains("技能差距"),eq("alice"),eq("r1"));
        verifyNoInteractions(sink);
    }
}
