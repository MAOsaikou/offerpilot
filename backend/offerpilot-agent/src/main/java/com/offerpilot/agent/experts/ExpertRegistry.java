package com.offerpilot.agent.experts;

import com.offerpilot.agent.application.ApprovalService;
import com.offerpilot.agent.application.ResumePatchWriteTools;
import com.offerpilot.agent.application.BoardWriteTools;
import com.offerpilot.agent.application.CareerWriteTools;
import com.offerpilot.agent.application.InterviewWriteTools;
import com.offerpilot.interview.application.InterviewCoachForChat;
import com.offerpilot.interview.application.InterviewRubric;
import com.offerpilot.career.application.CareerRecommendForChat;
import com.offerpilot.agent.domain.ExpertType;
import com.offerpilot.agent.domain.PlanStep;
import com.offerpilot.job.application.JobMatchCards;
import com.offerpilot.job.application.JobMatchForChat;
import com.offerpilot.job.application.JobSearchForChat;
import com.offerpilot.job.application.JobDescriptionForChat;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.resume.application.ResumeOptimizationForChat;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/** 已接入文字面试与规则评分、职业推荐与目标审批、岗位搜索、匹配、审批看板写入、简历诊断与定向改写；成长教练输出确定性技能差距与学习路线及审批任务写入，并可引用已入库 STAR 故事。 */
@Component
public class ExpertRegistry {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    private static final Map<ExpertType, String> LABELS = Map.of(
            ExpertType.CAREER_DISCOVERY, "职业探索", ExpertType.RESUME, "简历指导",
            ExpertType.JOB, "岗位分析", ExpertType.INTERVIEWER, "面试练习", ExpertType.COACH, "成长教练");
    private com.offerpilot.common.artifact.JobArtifacts artifacts;
    @Autowired(required = false)
    public void setArtifacts(com.offerpilot.common.artifact.JobArtifacts artifacts) { this.artifacts=artifacts; }
    public Mono<Map<String,Object>> withJobContext(String owner,Map<String,Object> context) {
        // A selected job is a tool input. Optional artifact visibility must not rewrite it.
        if (artifacts==null || text(context.get("jobId"))!=null) return Mono.just(context);
        return Mono.fromCallable(() -> resolveJobContext(owner,context)).subscribeOn(Schedulers.boundedElastic());
    }
    private Map<String,Object> resolveJobContext(String owner,Map<String,Object> context) {
        var selected=new java.util.LinkedHashMap<String,Object>(context);
        String job=optionalJob(owner,null,text(context.get("applicationId")),
            text(context.getOrDefault("matchId",context.get("jobMatchId"))));
        if (job!=null) selected.put("jobId",job);
        return selected;
    }
    private String optionalJob(String owner,String jobId,String applicationId,String matchId) {
        if (artifacts==null) return jobId;
        try {
            return artifacts.selectedJob(owner,jobId,applicationId,matchId);
        } catch (BusinessException ex) {
            if (ex.errorCode()!=ErrorCode.JOB_NOT_FOUND && ex.errorCode()!=ErrorCode.APPLICATION_NOT_FOUND
                    && ex.errorCode()!=ErrorCode.MATCH_NOT_FOUND) throw ex;
            // Missing optional provenance does not authorize or invalidate the tool input.
            return null;
        }
    }
    private final JobMatchForChat matches;
    private com.offerpilot.resume.application.ResumeDiagnosisForChat diagnoses;
    private JobSearchForChat searches;
    private ResumeOptimizationForChat optimizations;
    private JobDescriptionForChat descriptions;
    private BoardWriteTools boards;
    private CareerRecommendForChat careers;
    private CareerWriteTools careerWrites;
    private InterviewCoachForChat interviews;
    private InterviewWriteTools interviewWrites;
    private com.offerpilot.career.application.CoachPlanForChat coach;
    private com.offerpilot.agent.application.CoachWriteTools coachWrites;
    private ResumePatchWriteTools patchWrites;
    private KnowledgeNoteContext noteContext;
    private StarStoryContext starContext;
    private com.offerpilot.agent.application.StarStoryWriteTools starWrites;
    private com.offerpilot.interview.application.StarStoryService starStories;

    @Autowired
    public void setNoteContext(KnowledgeNoteContext noteContext) { this.noteContext = noteContext; }

    @Autowired(required = false)
    public void setStarContext(StarStoryContext starContext) { this.starContext = starContext; }

    @Autowired(required = false)
    public void setStarWrites(com.offerpilot.agent.application.StarStoryWriteTools starWrites) { this.starWrites = starWrites; }

    @Autowired(required = false)
    public void setStarStories(com.offerpilot.interview.application.StarStoryService starStories) { this.starStories = starStories; }

    private Mono<ExpertResult> withNotes(String owner, PlanStep step, Mono<ExpertResult> work) {
        if (noteContext == null) return work;
        return work.flatMap(result -> noteContext.attach(owner, text(step.inputs().get("noteQuery")), result));
    }

    private Mono<ExpertResult> withStories(String owner, PlanStep step, Mono<ExpertResult> work) {
        if (starContext == null) return work;
        if (step.agent() != ExpertType.INTERVIEWER && step.agent() != ExpertType.COACH) return work;
        String query = storyQuery(step);
        return work.flatMap(result -> {
            if (step.agent() != ExpertType.INTERVIEWER || interviews == null) return starContext.attach(owner, query, result);
            // Use the actual outcome session, including a newly created/advanced session after approval.
            String id = text(step.inputs().get("sessionId"));
            for (var card : result.cards()) {
                if (card.payload() instanceof InterviewRubric.Report report) id = report.id();
                if (card.payload() instanceof InterviewCoachForChat.Turn turn) id = turn.sessionId();
            }
            if (id == null) return Mono.just(result);
            return starContext.attachInterview(owner, id, query, result);
        });
    }

    /** Prefer current-turn noteQuery (bounded), then role; coach falls back to skill/STAR practice terms. */
    private static String storyQuery(PlanStep step) {
        Object raw = step.inputs().get("noteQuery");
        if (raw instanceof String s && !s.isBlank()) {
            String trimmed = s.strip();
            return trimmed.length() <= 200 ? trimmed : com.offerpilot.common.text.TextBounds.truncate(trimmed, 200);
        }
        String role = text(step.inputs().get("role"));
        if (role != null) return role;
        return step.agent() == ExpertType.COACH ? "项目 技能 面试 STAR" : "面试 STAR";
    }

    public ExpertRegistry() {
        this.matches = null;
    }

    public ExpertRegistry(JobMatchForChat matches) {
        this.matches = matches;
    }

    public ExpertRegistry(ObjectProvider<JobMatchForChat> matches, ObjectProvider<com.offerpilot.resume.application.ResumeDiagnosisForChat> diagnoses) {
        this.matches = matches.getIfAvailable();
        this.diagnoses = diagnoses.getIfAvailable();
    }

    public ExpertRegistry(ObjectProvider<JobMatchForChat> matches,
                          ObjectProvider<com.offerpilot.resume.application.ResumeDiagnosisForChat> diagnoses,
                          JobSearchForChat searches) {
        this(matches, diagnoses);
        this.searches = searches;
    }

    public ExpertRegistry(ObjectProvider<JobMatchForChat> matches,
                          ObjectProvider<com.offerpilot.resume.application.ResumeDiagnosisForChat> diagnoses,
                          JobSearchForChat searches, ResumeOptimizationForChat optimizations, JobDescriptionForChat descriptions) {
        this(matches, diagnoses, searches);
        this.optimizations = optimizations;
        this.descriptions = descriptions;
    }

    public ExpertRegistry(ObjectProvider<JobMatchForChat> matches,
                          ObjectProvider<com.offerpilot.resume.application.ResumeDiagnosisForChat> diagnoses,
                          JobSearchForChat searches, ResumeOptimizationForChat optimizations, JobDescriptionForChat descriptions,
                          BoardWriteTools boards) {
        this(matches, diagnoses, searches, optimizations, descriptions);
        this.boards = boards;
    }

    public ExpertRegistry(ObjectProvider<JobMatchForChat> matches,
                          ObjectProvider<com.offerpilot.resume.application.ResumeDiagnosisForChat> diagnoses,
                          JobSearchForChat searches, ResumeOptimizationForChat optimizations, JobDescriptionForChat descriptions,
                          BoardWriteTools boards, ObjectProvider<CareerRecommendForChat> careers,
                          ObjectProvider<CareerWriteTools> careerWrites) {
        this(matches, diagnoses, searches, optimizations, descriptions, boards);
        this.careers = careers.getIfAvailable();
        this.careerWrites = careerWrites.getIfAvailable();
    }

    public ExpertRegistry(ObjectProvider<JobMatchForChat> matches,
                          ObjectProvider<com.offerpilot.resume.application.ResumeDiagnosisForChat> diagnoses,
                          JobSearchForChat searches, ResumeOptimizationForChat optimizations, JobDescriptionForChat descriptions,
                          BoardWriteTools boards, ObjectProvider<CareerRecommendForChat> careers,
                          ObjectProvider<CareerWriteTools> careerWrites, ObjectProvider<InterviewCoachForChat> interviews,
                          ObjectProvider<InterviewWriteTools> interviewWrites) {
        this(matches, diagnoses, searches, optimizations, descriptions, boards, careers, careerWrites);
        this.interviews = interviews.getIfAvailable();
        this.interviewWrites = interviewWrites.getIfAvailable();
    }

    public ExpertRegistry(ObjectProvider<JobMatchForChat> matches,
                          ObjectProvider<com.offerpilot.resume.application.ResumeDiagnosisForChat> diagnoses,
                          JobSearchForChat searches, ResumeOptimizationForChat optimizations, JobDescriptionForChat descriptions,
                          BoardWriteTools boards, ObjectProvider<CareerRecommendForChat> careers,
                          ObjectProvider<CareerWriteTools> careerWrites, ObjectProvider<InterviewCoachForChat> interviews,
                          ObjectProvider<InterviewWriteTools> interviewWrites,
                          ObjectProvider<com.offerpilot.career.application.CoachPlanForChat> coach,
                          ObjectProvider<com.offerpilot.agent.application.CoachWriteTools> coachWrites) {
        this(matches, diagnoses, searches, optimizations, descriptions, boards, careers, careerWrites, interviews, interviewWrites);
        this.coach = coach.getIfAvailable();
        this.coachWrites = coachWrites.getIfAvailable();
    }

    @Autowired
    public ExpertRegistry(ObjectProvider<JobMatchForChat> matches,
                          ObjectProvider<com.offerpilot.resume.application.ResumeDiagnosisForChat> diagnoses,
                          JobSearchForChat searches, ResumeOptimizationForChat optimizations, JobDescriptionForChat descriptions,
                          BoardWriteTools boards, ObjectProvider<CareerRecommendForChat> careers,
                          ObjectProvider<CareerWriteTools> careerWrites, ObjectProvider<InterviewCoachForChat> interviews,
                          ObjectProvider<InterviewWriteTools> interviewWrites,
                          ObjectProvider<com.offerpilot.career.application.CoachPlanForChat> coach,
                          ObjectProvider<com.offerpilot.agent.application.CoachWriteTools> coachWrites,
                          ObjectProvider<ResumePatchWriteTools> patchWrites) {
        this(matches, diagnoses, searches, optimizations, descriptions, boards, careers, careerWrites, interviews, interviewWrites, coach, coachWrites);
        this.patchWrites = patchWrites.getIfAvailable();
    }
    public boolean writingPatches(PlanStep step) { return ResumePatchWriteTools.recognizes(step); }
    public ApprovalService.Ticket requestPatch(String owner, String runId, PlanStep step) {
        if (patchWrites == null) throw new IllegalStateException("简历补丁工具暂不可用");
        return patchWrites.request(owner, runId, step);
    }
    public Mono<ExpertResult> executePatch(String owner, String runId, PlanStep step, ApprovalService.Decision decision) {
        return patchWrites.execute(owner, runId, step, decision);
    }

    public boolean writingCoach(PlanStep step) { return com.offerpilot.agent.application.CoachWriteTools.recognizes(step); }
    public ApprovalService.Ticket requestCoach(String owner, String runId, PlanStep step) {
        if (coachWrites == null) throw new IllegalStateException("成长任务工具暂不可用");
        return coachWrites.request(owner, runId, step);
    }
    public Mono<ExpertResult> executeCoach(String owner, PlanStep step, ApprovalService.Decision decision) {
        return coachWrites.execute(owner, step, decision);
    }

    public boolean interviewing(PlanStep step) { return step.agent() == ExpertType.INTERVIEWER && interviews != null; }
    public boolean writingInterview(PlanStep step) { return InterviewWriteTools.recognizes(step); }
    public ApprovalService.Ticket requestInterview(String owner, String runId, PlanStep step) {
        if (interviewWrites == null) throw new IllegalStateException("面试工具暂不可用");
        if ("startInterview".equals(step.inputs().get("action")) && step.inputs().containsKey("jobId")) {
            var inputs=new java.util.LinkedHashMap<String,Object>(step.inputs());
            String job=optionalJob(owner,text(inputs.remove("jobId")),null,null);
            if (job!=null) inputs.put("jobId",job);
            step=new PlanStep(step.id(),step.title(),step.agent(),inputs,step.dependsOn());
        }
        return interviewWrites.request(owner, runId, step);
    }
    public Mono<ExpertResult> executeInterview(String owner, String runId, PlanStep step, ApprovalService.Decision decision) {
        return withStories(owner, step, withNotes(owner, step, interviewWrites.execute(owner, runId, step, decision)));
    }

    public boolean writingStarStory(PlanStep step) {
        return starWrites != null && com.offerpilot.agent.application.StarStoryWriteTools.recognizes(step);
    }
    public ApprovalService.Ticket requestStarStory(String owner, String runId, PlanStep step) {
        if (starWrites == null) throw new IllegalStateException("STAR 故事工具暂不可用");
        return starWrites.request(owner, runId, step);
    }
    public Mono<ExpertResult> executeStarStory(String owner, String runId, PlanStep step, ApprovalService.Decision decision) {
        return starWrites.execute(owner, runId, step, decision);
    }

    public boolean recommending(PlanStep step) {
        return careers != null && step.agent() == ExpertType.CAREER_DISCOVERY && !writingCareer(step);
    }

    public boolean writingCareer(PlanStep step) { return CareerWriteTools.recognizes(step); }

    public ApprovalService.Ticket requestCareer(String owner, String runId, PlanStep step) {
        if (careerWrites == null) throw new IllegalStateException("职业目标工具不可用");
        return careerWrites.request(owner, runId, step);
    }

    public Mono<ExpertResult> executeCareer(String owner, PlanStep step, ApprovalService.Decision decision) {
        return careerWrites.execute(owner, step, decision);
    }

    public boolean writingBoard(PlanStep step) {
        return BoardWriteTools.recognizes(step);
    }

    public ApprovalService.Ticket requestBoard(String owner, String runId, PlanStep step) {
        if (boards == null) throw new IllegalStateException("看板工具不可用");
        return boards.request(owner, runId, step);
    }

    public Mono<ExpertResult> executeBoard(String owner, String runId, PlanStep step,
            ApprovalService.Decision decision) {
        return boards.execute(owner, runId, step, decision);
    }

    public boolean optimizing(PlanStep step, Map<String,Object> context) {
        if (optimizations == null || step.agent() != ExpertType.RESUME || context == null
                || text(context.get("resumeId")) == null || text(context.get("resumeVersionId")) == null) return false;
        return "read_optimization".equals(step.inputs().get("action"))
                ? text(context.get("optimizationReportId")) != null
                : "optimize".equals(step.inputs().get("action")) && descriptions != null && text(context.get("jobId")) != null;
    }

    public boolean searching(PlanStep step) {
        return searches != null && step.agent() == ExpertType.JOB && "search".equals(step.inputs().get("action"));
    }

    public boolean diagnosing(PlanStep step, Map<String,Object> context) {
        return diagnoses != null && step.agent() == ExpertType.RESUME && context != null
            && !writingPatches(step) && !"optimize".equals(step.inputs().get("action")) && !"read_optimization".equals(step.inputs().get("action")) && !"advice".equals(step.inputs().get("action"))
            && text(context.get("resumeId")) != null && text(context.get("resumeVersionId")) != null;
    }

    public String label(ExpertType type) {
        return LABELS.get(type);
    }

    public String capabilities() {
        return java.util.Arrays.stream(ExpertType.values())
                .map(type -> type.name() + ": " + label(type)).collect(java.util.stream.Collectors.joining("\n"));
    }

    /** 显式 match 必须执行或明确失败；旧无动作步骤仅在服务和所选资源齐全时匹配。 */
    public boolean matching(PlanStep step, Map<String, Object> context) {
        return step.agent() == ExpertType.JOB && !writingBoard(step) && !"search".equals(step.inputs().get("action")) && !"getApplicationDetail".equals(step.inputs().get("action"))
                && ("match".equals(step.inputs().get("action")) || matches != null && read(context) != null);
    }

    public Mono<ExpertResult> execute(String owner, Map<String, Object> context, String runId, PlanStep step,
                                      Map<String, String> upstreamOutputs) {
        return execute(owner, context, runId, step, upstreamOutputs, null);
    }

    public Mono<PlanStep> prepareInterview(String owner, PlanStep step) {
        if (interviews == null || step.agent() != ExpertType.INTERVIEWER
                || !"continueInterview".equals(step.inputs().get("action"))) return Mono.just(step);
        return Mono.fromCallable(() -> {
            String id = interviews.resolveSession(owner, text(step.inputs().get("sessionId")), false);
            var session = interviews.session(owner, id);
            var inputs = new java.util.LinkedHashMap<>(step.inputs());
            inputs.put("sessionId", id);
            if (session.completed()) {
                inputs.remove("answer");
                inputs.remove("nextQuestion");
                inputs.put("action", "completedInterview");
            }
            return new PlanStep(step.id(), step.title(), step.agent(), inputs, step.dependsOn());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<ExpertResult> execute(String owner, Map<String, Object> context, String runId, PlanStep step,
                                      Map<String, String> upstreamOutputs, com.offerpilot.llm.ModelTier tier) {
        if (writingBoard(step) || writingCareer(step) || writingInterview(step) || writingCoach(step) || writingPatches(step) || writingStarStory(step)) return Mono.error(new IllegalStateException("写入必须先审批"));
        var work = executeRead(owner, context, runId, step, upstreamOutputs, tier);
        Mono<ExpertResult> enriched = step.agent() == ExpertType.COACH || step.agent() == ExpertType.INTERVIEWER
                || (step.agent() == ExpertType.RESUME && "advice".equals(step.inputs().get("action")))
                ? withNotes(owner, step, work) : work;
        return withStories(owner, step, enriched);
    }

    private Mono<ExpertResult> executeRead(String owner, Map<String, Object> context, String runId, PlanStep step,
                                         Map<String, String> upstreamOutputs, com.offerpilot.llm.ModelTier tier) {
        if (step.agent() == ExpertType.COACH) {
            if (coach == null) return Mono.just(ExpertResult.text("成长教练服务暂不可用，请稍后重试。"));
            return Mono.fromCallable(() -> {
                String resumeId = context == null ? null : text(context.get("resumeId"));
                String versionId = context == null ? null : text(context.get("resumeVersionId"));
                String jobId = context == null ? null : text(context.get("jobId"));
                String applicationId = context == null ? null : text(context.get("applicationId"));
                var result = jobId == null && applicationId == null
                        ? coach.plan(owner, resumeId, versionId, requestId(runId, step.id()))
                        : coach.plan(owner, resumeId, versionId, requestId(runId, step.id()), jobId, applicationId);
                return result.skillGap() == null ? ExpertResult.text(result.summary()) : ExpertResult.cards(result.summary(),
                        new ExpertResult.Card(requestId(runId, step.id() + ":gap"), "skill_gap", result.skillGap()),
                        new ExpertResult.Card(requestId(runId, step.id() + ":roadmap"), "roadmap", result.roadmap()));
            }).subscribeOn(Schedulers.boundedElastic());
        }
        if (step.agent() == ExpertType.INTERVIEWER) {
            if (interviews == null) return Mono.just(ExpertResult.text("文字面试服务暂不可用，请稍后重试。"));
            if ("completedInterview".equals(step.inputs().get("action")))
                return Mono.just(ExpertResult.text("这场面试已完成，本轮没有保存或推进答案。你可以请求评分、复盘，或开始一场新面试。"));
            if ("recallStarStories".equals(step.inputs().get("action"))) {
                return Mono.fromCallable(() -> {
                    String id = interviews.resolveSession(owner, text(step.inputs().get("sessionId")), false);
                    var session = interviews.session(owner, id);
                    var answer = session.answers().get(session.questionIndex());
                    var turn = new InterviewCoachForChat.Turn(id, "ASKING", session.questionIndex(), session.answers().size(),
                            answer.questionText(), "请参考已确认的故事，补充本题的真实背景、职责、行动和结果。未保存或推进答案。");
                    return ExpertResult.card(turn.note(), requestId(runId, step.id()), "interviewer_state", turn);
                }).subscribeOn(Schedulers.boundedElastic());
            }
            if (!java.util.Set.of("scoreInterview", "generateReport").contains(String.valueOf(step.inputs().get("action"))))
                return Mono.just(ExpertResult.text("请开始文字模拟面试，或请求面试评分卡。"));
            return Mono.fromCallable(() -> {
                String id = interviews.resolveSession(owner, (String) step.inputs().get("sessionId"), false);
                var report = interviews.scoreSession(owner, id);
                if (starStories == null) {
                    return ExpertResult.card("当前会话的文字规则评分（未修改完成状态）。" + InterviewRubric.summary(report),
                            requestId(runId, step.id()), "interview_report", report);
                }
                var candidates = starStories.candidates(owner, id).stream().filter(c -> !c.alreadySaved()).toList();
                if (candidates.isEmpty()) {
                    return ExpertResult.card("当前会话的文字规则评分（未修改完成状态）。" + InterviewRubric.summary(report),
                            requestId(runId, step.id()), "interview_report", report);
                }
                return ExpertResult.cards("当前会话的文字规则评分（未修改完成状态）。" + InterviewRubric.summary(report) + " 可审批入库的 STAR 候选：" + candidates.size() + " 条。",
                        new ExpertResult.Card(requestId(runId, step.id()), "interview_report", report),
                        new ExpertResult.Card(requestId(runId, step.id() + ":stars"), "star_story_candidates", java.util.Map.of("items", candidates, "count", candidates.size())));
            }).subscribeOn(Schedulers.boundedElastic());
        }
        if (step.agent() == ExpertType.CAREER_DISCOVERY) {
            if (careers == null) return Mono.just(ExpertResult.text("职业推荐服务暂不可用，请稍后重试。未修改职业档案。"));
            return Mono.fromCallable(() -> {
                var result = careers.recommend(owner, text(step.inputs().get("request")));
                return result.payload() == null ? ExpertResult.text(result.guidance())
                        : ExpertResult.card("根据已保存兴趣生成的规则推荐（未设置目标）。",
                                requestId(runId, step.id()), "career_recommendation", result.payload());
            }).subscribeOn(Schedulers.boundedElastic());
        }
        if (step.agent() == ExpertType.JOB && "getApplicationDetail".equals(step.inputs().get("action"))) {
            if (boards == null) return Mono.error(new IllegalStateException("看板服务不可用"));
            return boards.detail(owner, text(step.inputs().get("applicationId")));
        }
        if (searching(step)) {
            return Mono.fromCallable(() -> {
                var inputs = step.inputs();
                var card = searches.search((String) inputs.get("query"), (String) inputs.get("site"),
                        (String) inputs.get("nature"), (String) inputs.get("location"));
                String sourceLabel = "LOCAL_SNAPSHOT".equals(card.path("meta").path("source").asText())
                        ? "离线示例岗位快照（人工整理，非实时数据，不代表当前开放岗位）："
                        : "企业官网岗位搜索结果（仅含本次返回结果，不是全站总数）：";
                return ExpertResult.card(sourceLabel + jsonSummary(card),
                        requestId(runId, step.id()), "job_card", card);
            }).subscribeOn(Schedulers.boundedElastic());
        }
        if (optimizing(step, context)) {
            String resumeId = text(context.get("resumeId")), versionId = text(context.get("resumeVersionId"));
            Mono<com.fasterxml.jackson.databind.JsonNode> result = "read_optimization".equals(step.inputs().get("action"))
                    ? optimizations.report(owner, resumeId, versionId, text(context.get("optimizationReportId")))
                    : Mono.fromCallable(() -> descriptions.description(text(context.get("jobId"))))
                        .subscribeOn(Schedulers.boundedElastic()).flatMap(jd -> optimizations.generate(owner, resumeId, versionId, jd,
                            requestId(runId, step.id()), optionalJob(owner,text(context.get("jobId")),null,null)));
            return result.map(report -> ExpertResult.card("已保存的改写建议（本轮未执行采纳，采纳状态请在简历页查看）：" + jsonSummary(report),
                    report.path("reportId").asText(), "resume_diff", report));
        }
        if ("advice".equals(step.inputs().get("action")) && step.agent() == ExpertType.RESUME)
            return Mono.just(ExpertResult.text("本轮仅提供简历文字指导；没有生成报告或修改简历。采纳须进入定向改写页逐条确认并保存。"));
        if (diagnosing(step, context)) {
            return diagnoses.execute(owner,text(context.get("resumeId")),text(context.get("resumeVersionId")),
                text(context.get("diagnosisReportId")),requestId(runId,step.id())).map(report ->
                    ExpertResult.card("已保存的简历诊断：" + jsonSummary(report),report.path("reportId").asText(),"resume_diagnosis",report));
        }
        if (step.agent() == ExpertType.RESUME) {
            return Mono.just(ExpertResult.text("未选择已保存的简历版本。请从简历详情点击 AI 诊断或继续讨论；不能读取或猜测用户未提供的简历。"));
        }
        if (step.agent() == ExpertType.JOB && "match".equals(step.inputs().get("action"))) {
            if (read(context) == null) return Mono.error(new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "未执行匹配：请先选择岗位、已保存的简历和简历版本。"));
            if (matches == null) return Mono.error(new BusinessException(ErrorCode.MODEL_UNAVAILABLE,
                    "岗位匹配服务暂不可用，请稍后重试。"));
        }
        Target target = matching(step, context) ? read(context) : null;
        if (target != null && matches != null) {
            String requestId = requestId(runId, step.id());
            return Mono.fromCallable(() -> {
                JobMatchCards.Card card = tier == null
                        ? matches.match(owner, target.jobId(), target.resumeId(), target.resumeVersionId(), requestId)
                        : matches.match(owner, target.jobId(), target.resumeId(), target.resumeVersionId(), requestId, tier);
                                var payload = card.payload();
                if (payload != null && payload.path("evaluation").isObject()) {
                    return ExpertResult.cards(card.summary(),
                            new ExpertResult.Card(card.cardId(), card.cardType(), payload),
                            new ExpertResult.Card(requestId(runId, step.id() + ":eval"), "job_eval_report", payload.path("evaluation")));
                }
                return ExpertResult.card(card.summary(), card.cardId(), card.cardType(), payload);
            }).subscribeOn(Schedulers.boundedElastic());
        }
        return Mono.fromSupplier(() -> ExpertResult.text(label(step.agent())
                + "本轮未执行搜索、修改或创建业务记录，可继续提供文字建议。"));
    }

    /** JsonNode is intentionally serialized as JSON; arbitrary Java objects never enter tool narration. */
    private static String jsonSummary(com.fasterxml.jackson.databind.JsonNode data) {
        try {
            return JSON.writeValueAsString(data);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new BusinessException(ErrorCode.STRUCTURED_OUTPUT_INVALID, "工具结果暂时无法展示，请重试。");
        }
    }

    private static Target read(Map<String, Object> source) {
        if (source == null) return null;
        String jobId = text(source.get("jobId"));
        String resumeId = text(source.get("resumeId"));
        String versionId = text(source.get("resumeVersionId"));
        if (jobId == null || resumeId == null || versionId == null) return null;
        return new Target(jobId, resumeId, versionId);
    }

    private static String text(Object value) {
        if (!(value instanceof String raw)) return null;
        String trimmed = raw.strip();
        if (trimmed.isEmpty() || trimmed.length() > 80) return null;
        return trimmed;
    }

    public static String requestId(String runId, String stepId) {
        return UUID.nameUUIDFromBytes((runId + ":" + stepId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private record Target(String jobId, String resumeId, String resumeVersionId) {
    }
}
