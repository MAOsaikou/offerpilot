package com.offerpilot.board.application;
import com.offerpilot.common.notification.NotificationSink;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import java.sql.ResultSet;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class DeadlineRemindersTest {
    @Test void onlyStrictRealCalendarDatesAreRemindable() {
        assertThat(DeadlineReminders.date("2026-02-28")).isEqualTo(LocalDate.of(2026,2,28));
        for(String raw:new String[]{"2026-02-30","2026-2-28","长期有效","2026-10-23T00:00:00Z",""}) assertThat(DeadlineReminders.date(raw)).isNull();
        assertThat(DeadlineReminders.date(null)).isNull();
    }
    @Test void sendsThreeAndOneDayKeysSeparatelyAndInterviewActionUsesItsOwnDate() throws Exception {
        var jdbc=mock(JdbcTemplate.class);var sink=mock(NotificationSink.class);var rs=mock(ResultSet.class);
        when(rs.getString("user_id")).thenReturn("alice");when(rs.getString("id")).thenReturn("app");when(rs.getString("title")).thenReturn("岗位");
        when(rs.getString("deadline")).thenReturn("2026-10-23");when(rs.getString("status")).thenReturn("INTERVIEW");
        when(rs.getString("next_action_at")).thenReturn("2026-10-21");when(rs.getString("next_action")).thenReturn("准备面试");
        doAnswer(c->{c.<RowCallbackHandler>getArgument(1).processRow(rs);return null;}).when(jdbc).query(anyString(),any(RowCallbackHandler.class));
        var job=new DeadlineReminders(jdbc,sink);job.remind(LocalDate.of(2026,10,20));job.remind(LocalDate.of(2026,10,22));
        verify(sink).send(eq("alice"),eq("DDL"),eq("ddl:app:2026-10-23:3"),anyString(),anyString(),eq("/board?application=app"));
        verify(sink).send(eq("alice"),eq("DDL"),eq("ddl:app:2026-10-23:1"),anyString(),anyString(),anyString());
        verify(sink).send(eq("alice"),eq("INTERVIEW_PLAN"),eq("interview-action:app:2026-10-21"),anyString(),anyString(),anyString());
        verifyNoMoreInteractions(sink);
        verify(jdbc,times(2)).query(contains("a.status NOT IN ('CLOSED','OFFER')"),any(RowCallbackHandler.class));
    }
}
