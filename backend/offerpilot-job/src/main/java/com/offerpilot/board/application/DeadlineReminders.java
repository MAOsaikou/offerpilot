package com.offerpilot.board.application;

import com.offerpilot.common.notification.NotificationSink;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.List;

@Service
public class DeadlineReminders {
    private final JdbcTemplate jdbc; private final NotificationSink notices;
    public DeadlineReminders(JdbcTemplate jdbc, NotificationSink notices) { this.jdbc=jdbc; this.notices=notices; }
    @Scheduled(cron="${offerpilot.notifications.deadline-cron:0 */30 * * * *}",zone="Asia/Shanghai")
    public void scheduled() { remind(LocalDate.now(ZoneId.of("Asia/Shanghai"))); }
    public void remind(LocalDate today) {
        // Dates are parsed in Java: malformed/unknown upstream DDL cannot fail the entire scan.
        jdbc.query("""
            SELECT a.id,a.user_id,a.status,a.next_action,a.next_action_at,j.payload->>'deadline' AS deadline,
                   coalesce(j.payload->>'title','岗位') AS title
            FROM applications a JOIN jobs j ON j.id=a.job_id JOIN users u ON u.id=a.user_id
            WHERE a.status NOT IN ('CLOSED','OFFER') AND u.status='ACTIVE' AND coalesce(j.payload->>'source','') NOT IN ('demo','LOCAL_SNAPSHOT') AND j.site<>'demo'
            """, rs -> {
            String owner=rs.getString("user_id"), id=rs.getString("id"), title=rs.getString("title");
            LocalDate ddl=date(rs.getString("deadline"));
            if (ddl != null) for (int days : List.of(3,1)) if (ddl.equals(today.plusDays(days)))
                notices.send(owner,"DDL","ddl:"+id+":"+ddl+":"+days,"投递截止还有"+days+"天",
                    title+" · 截止日期 "+ddl,"/board?application="+id);
            LocalDate action=date(rs.getString("next_action_at"));
            if ("INTERVIEW".equals(rs.getString("status")) && action != null && action.equals(today.plusDays(1)))
                notices.send(owner,"INTERVIEW_PLAN","interview-action:"+id+":"+action,"明天有面试行动",
                    title+" · "+rs.getString("next_action")+" · "+action,"/board?application="+id);
        });
    }
    static LocalDate date(String raw) {
        if (raw==null || !raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return null;
        try { return LocalDate.parse(raw); } catch (DateTimeParseException ex) { return null; }
    }
}
