package com.offerpilot.agent.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.offerpilot.board.application.ApplicationService;
import com.offerpilot.career.application.CoachEvidence;
import com.offerpilot.career.application.CoachPlanForChat.Evidence;
import com.offerpilot.interview.application.InterviewService;
import com.offerpilot.interview.application.InterviewRubric;
import com.offerpilot.job.application.JobMatchService;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.StreamSupport;

/** Bridges domain application readers; neither the planner nor narration chooses evidence. */
@Component
public class CoachEvidenceAdapter implements CoachEvidence {
    private final JobMatchService matches;
    private final InterviewService interviews;
    private final ApplicationService applications;
    public CoachEvidenceAdapter(JobMatchService matches, InterviewService interviews, ApplicationService applications) {
        this.matches = matches; this.interviews = interviews; this.applications = applications;
    }
    @Override public Snapshot read(String owner, String jobId, String applicationId, String target) {
        if (applicationId != null) jobId = applications.detail(owner, applicationId).application().jobId();
        var report = matches.latestCompleted(owner, jobId, target);
        var session = interviews.latestCompleted(owner);
        var gaps = new ArrayList<Gap>();
        if (report != null) {
            String id = report.path("id").asText(), selectedJob = report.path("jobId").asText();
            String label = "岗位匹配 · " + report.path("jobSnapshot").path("title").asText(target);
            for (String status : List.of("MISSING", "PARTIAL")) {
                int count = 0;
                for (var item : report.path("requirements")) {
                    String name = item.path("requirement").asText().strip();
                    if (!status.equals(item.path("status").asText()) || name.isBlank()) continue;
                    gaps.add(new Gap(name, status.equals("MISSING") ? 0 : 20, status.equals("MISSING") ? 0 : 50,
                            new Evidence("JOB_MATCH", id, label, bounded(status + "：" + name
                                    + "；" + item.path("reason").asText()), selectedJob)));
                    if (++count == 2) break;
                }
            }
            StreamSupport.stream(report.path("evaluation").path("dimensions").spliterator(), false)
                    .filter(d -> d.path("score").asInt() < 60)
                    .sorted(Comparator.comparingInt((JsonNode d) -> d.path("score").asInt())
                            .thenComparing(d -> d.path("code").asText())).limit(2)
                    .forEach(d -> gaps.add(new Gap("岗位评估：" + d.path("name").asText(),
                            30, d.path("score").asInt(), new Evidence("JOB_MATCH", id, label,
                            bounded(d.path("score").asInt() + "/100；" + d.path("reason").asText()), selectedJob))));
        }
        if (session != null) {
            var rubric = session.generationSource()==null ? InterviewRubric.score(session) : interviews.report(owner,session.id());
            var d = rubric.dimensions();
            String label = "面试复盘 · " + session.role();
            var dimensions = new HashMap<String,Integer>();
            if (d.professional()!=null) dimensions.put("专业内容",d.professional());
            if (d.expression()!=null) dimensions.put("表达清晰度",d.expression());
            if (d.logic()!=null) dimensions.put("逻辑结构",d.logic());
            if (d.jobFit()!=null) dimensions.put("岗位关联",d.jobFit());
            dimensions.entrySet().stream().filter(e -> e.getValue() < 60)
                    .sorted(Map.Entry.<String,Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                    .limit(2).forEach(e -> gaps.add(new Gap("面试表现：" + e.getKey(), 10, e.getValue(),
                            new Evidence("INTERVIEW", session.id(), label, e.getKey() + "：" + e.getValue() + (session.generationSource()==null ? "/100（文字规则线索）" : "/100（模型评价）"), session.jobId()))));
            // Retain at most two lowest-STAR answers; never copy full private answers into prompts/cards.
            rubric.turns().stream().filter(t -> session.generationSource()==null && t.star().count() < 3)
                    .sorted(Comparator.comparingInt(t -> t.star().count())).limit(2)
                    .forEach(t -> gaps.add(new Gap("STAR 回答结构", 11, t.star().count() * 25,
                            new Evidence("INTERVIEW", session.id(), label, bounded("STAR " + t.star().count()
                                    + "/4；题目：" + t.question() + "；" + t.feedback()), session.jobId()))));
        }
        return new Snapshot(report != null, session != null, List.copyOf(gaps));
    }
    private static String bounded(String value) {
        return value.length() <= 240 ? value : com.offerpilot.common.text.TextBounds.truncate(value, 240) + "…";
    }
}
