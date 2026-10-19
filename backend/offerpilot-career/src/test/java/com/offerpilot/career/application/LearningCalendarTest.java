package com.offerpilot.career.application;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
class LearningCalendarTest {
    @Test void exportsStableShanghaiEventsWithEscapedTextCrlfAndOctetFolding() {
        var item = new CoachPlanForChat.Item("item", "表达;复盘,练习\\证据\n第二行" + "中文😀".repeat(25), 2,
                List.of("根据原始面试报告核对"), null, false);
        var roadmap = new CoachPlanForChat.RoadmapPayload(List.of(new CoachPlanForChat.Week(2,"主题",List.of(item))),
                List.of(new CoachPlanForChat.Milestone("核对案例", "第2周末")));
        var plan = new InterviewLearningPlans.Plan("lp_1","iv_1","2026-10-19",roadmap);
        String ics = LearningCalendar.export(plan);
        assertThat(ics).startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n").endsWith("END:VCALENDAR\r\n")
                .contains("BEGIN:VTIMEZONE", "TZID:Asia/Shanghai", "DTSTART;TZID=Asia/Shanghai:20261026T190000", "DTEND;TZID=Asia/Shanghai:20261026T210000", "UID:lp_1-task-0@offerpilot.local");
        assertThat(ics.replace("\r\n ", "")).contains("表达\\;复盘\\,练习\\\\证据\\n第二行", "来源面试报告：iv_1", "里程碑：核对案例");
        for (String line : ics.split("\r\n")) assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
        assertThat(ics.replace("\r\n", "")).doesNotContain("\n", "\r");
        assertThat(LearningCalendar.export(plan)).isEqualTo(ics);
    }
}
