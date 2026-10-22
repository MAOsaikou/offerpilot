package com.offerpilot.career.application;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;

/** RFC 5545: CRLF, escaped TEXT, UTF-8 octet folding, stable UID and explicit Shanghai zone. */
public final class LearningCalendar {
    private LearningCalendar() {}
    public static String export(InterviewLearningPlans.Plan plan) {
        var out = new StringBuilder();
        for (String line : new String[]{"BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//OfferPilot//Learning Plan//ZH", "CALSCALE:GREGORIAN",
                "BEGIN:VTIMEZONE", "TZID:Asia/Shanghai", "BEGIN:STANDARD", "DTSTART:19700101T000000", "TZOFFSETFROM:+0800", "TZOFFSETTO:+0800", "TZNAME:CST", "END:STANDARD", "END:VTIMEZONE"}) line(out, line);
        var start = LocalDate.parse(plan.startDate());
        int event=0;
        for (var week : plan.roadmap().weeks()) {
            int day=0;
            for (var item : week.items()) {
                var at = start.plusWeeks(week.index()-1).plusDays(day++).atTime(19,0);
                event(out, plan, "task-" + event++, item.title(), String.join("\n", item.resources()), at, Math.max(1,item.hours()));
            }
        }
        for (int i=0; i<plan.roadmap().milestones().size(); i++) {
            var milestone = plan.roadmap().milestones().get(i);
            int week = i == 0 ? 2 : 4;
            event(out, plan, "milestone-"+i, "里程碑："+milestone.title(), milestone.at(), start.plusWeeks(week).minusDays(1).atTime(19,0), 1);
        }
        line(out,"END:VCALENDAR"); return out.toString();
    }
    private static void event(StringBuilder out, InterviewLearningPlans.Plan plan, String key, String title, String description, LocalDateTime at, int hours) {
        line(out,"BEGIN:VEVENT");
        line(out,"UID:"+plan.id()+"-"+key+"@offerpilot.local");
        line(out,"DTSTAMP:"+LocalDate.parse(plan.startDate()).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")));
        var format = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
        line(out,"DTSTART;TZID=Asia/Shanghai:"+at.format(format));
        line(out,"DTEND;TZID=Asia/Shanghai:"+at.plusHours(hours).format(format));
        line(out,"SUMMARY:"+escape(title));
        line(out,"DESCRIPTION:"+escape(description+"\n来源面试报告："+plan.reportId()));
        line(out,"END:VEVENT");
    }
    private static String escape(String value) { return value.replace("\\","\\\\").replace("\r\n","\n").replace("\r","\n").replace("\n","\\n").replace(";","\\;").replace(",","\\,"); }
    private static void line(StringBuilder out, String value) {
        int octets=0;
        for (int cp : value.codePoints().toArray()) {
            String ch = new String(Character.toChars(cp)); int bytes=ch.getBytes(StandardCharsets.UTF_8).length;
            if (octets+bytes>75) { out.append("\r\n "); octets=1; }
            out.append(ch); octets+=bytes;
        }
        out.append("\r\n");
    }
}
