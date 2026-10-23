package com.offerpilot.career.application;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.*;
import com.offerpilot.common.notification.NotificationSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
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
    @ParameterizedTest
    @CsvSource({"true,1,SUGGESTED", "false,1,SUGGESTED", "false,0,SUGGESTED", "true,0,FAILED"})
    void partialSearchOnlyFailsWhenNoJobsAreAvailable(boolean partial,int jobCount,String expectedStatus) throws Exception {
        var fixture=new GenerationFixture();
        var found=fixture.json.createObjectNode();
        var items=found.putArray("items");
        if(jobCount>0) items.addObject().put("id","job-1");
        found.putObject("meta").put("partial",partial);
        when(fixture.jobs.search("alice","开发","上海")).thenReturn(found);

        fixture.service.generate("alice");

        var savedResult=ArgumentCaptor.forClass(String.class);
        var savedNotice=ArgumentCaptor.forClass(String.class);
        verify(fixture.jdbc).update(contains("AND revision=? AND status='RUNNING'"),eq(expectedStatus),
            savedResult.capture(),savedNotice.capture(),eq("alice"),eq("r1"));
        var result=fixture.json.readTree(savedResult.getValue());
        assertThat(result.path("jobs")).isEqualTo(found);
        assertThat(result.path("skillGap").path("targetId").asText()).isEqualTo("occ-dev");
        assertThat(result.path("learning").path("summary").asText()).isEqualTo("学习建议");
        assertThat(savedNotice.getValue()).isEqualTo(partial?"岗位搜索仅返回部分结果，可重试。":"");
        verify(fixture.sink).send(eq("alice"),eq("CAREER_TARGET"),eq("target:r1"),anyString(),
            contains(savedNotice.getValue()),eq("/career"));
    }
    @ParameterizedTest
    @ValueSource(strings={"jobs","skillGap","learning"})
    void hardFailureStillFailsWithOtherSuccessfulParts(String failedPart) throws Exception {
        var fixture=new GenerationFixture();
        when(fixture.jobs.search("alice","开发","上海")).thenReturn(fixture.json.readTree(
            "{\"items\":[{\"id\":\"job-1\"}],\"meta\":{\"partial\":true}}"));
        String expectedNotice;
        switch(failedPart) {
            case "jobs" -> {
                when(fixture.jobs.search("alice","开发","上海")).thenThrow(new IllegalStateException("unavailable"));
                expectedNotice="岗位搜索未完成";
            }
            case "skillGap" -> {
                when(fixture.gaps.analyze("alice",null,null,null,null)).thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED));
                expectedNotice="技能差距未生成";
            }
            default -> {
                when(fixture.coach.plan("alice",null,null,"r1")).thenThrow(new BusinessException(ErrorCode.VALIDATION_FAILED));
                expectedNotice="学习建议未生成";
            }
        }

        fixture.service.generate("alice");

        var savedResult=ArgumentCaptor.forClass(String.class);
        verify(fixture.jdbc).update(contains("AND revision=? AND status='RUNNING'"),eq("FAILED"),
            savedResult.capture(),contains(expectedNotice),eq("alice"),eq("r1"));
        var result=fixture.json.readTree(savedResult.getValue());
        assertThat(result.has(failedPart)).isFalse();
        for(String part:List.of("jobs","skillGap","learning"))
            if(!part.equals(failedPart)) assertThat(result.path(part).isObject()).isTrue();
    }
    private static class GenerationFixture {
        final JdbcTemplate jdbc=mock(JdbcTemplate.class);
        final ObjectMapper json=new ObjectMapper();
        final TargetJobSearch jobs=mock(TargetJobSearch.class);
        final SkillGapService gaps=mock(SkillGapService.class);
        final CoachPlanForChat coach=mock(CoachPlanForChat.class);
        final NotificationSink sink=mock(NotificationSink.class);
        final CareerTargetPlans service;
        GenerationFixture() throws Exception {
            var rs=mock(ResultSet.class);var tx=mock(TransactionTemplate.class);
            when(rs.getString("revision")).thenReturn("r1");when(rs.getString("target")).thenReturn("开发");when(rs.getString("city")).thenReturn("上海");
            when(jdbc.query(anyString(),org.mockito.ArgumentMatchers.<RowMapper<CareerTargetPlans.Plan>>any(),eq("alice")))
                .thenAnswer(c->List.of(c.<RowMapper<CareerTargetPlans.Plan>>getArgument(1).mapRow(rs,0)));
            when(jdbc.update(contains("AND (status='PENDING'"),eq("alice"),eq("r1"))).thenReturn(1);
            when(gaps.analyze("alice",null,null,null,null)).thenReturn(
                new SkillGapService.Report("OCCUPATION","occ-dev","开发",null,List.of(),""));
            when(coach.plan("alice",null,null,"r1")).thenReturn(new CoachPlanForChat.Result("学习建议",null,null));
            doAnswer(c->{c.<java.util.function.Consumer<org.springframework.transaction.TransactionStatus>>getArgument(0)
                .accept(mock(org.springframework.transaction.TransactionStatus.class));return null;}).when(tx).executeWithoutResult(any());
            when(jdbc.queryForList(contains("FROM users"),eq(String.class),eq("alice"))).thenReturn(List.of("alice"));
            when(jdbc.update(contains("AND revision=? AND status='RUNNING'"),anyString(),anyString(),anyString(),eq("alice"),eq("r1"))).thenReturn(1);
            service=new CareerTargetPlans(jdbc,json,jobs,gaps,coach,sink,tx);
        }
    }
}
