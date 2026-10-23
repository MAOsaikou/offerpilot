package com.offerpilot.interview.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.id.IdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

@Service
public class InterviewService {
    private com.offerpilot.common.notification.NotificationSink notices;
    @org.springframework.beans.factory.annotation.Autowired
    public void setNotifications(com.offerpilot.common.notification.NotificationSink notices) { this.notices=notices; }
    public static final List<String> QUESTIONS = List.of(
            "请用两分钟介绍你自己，以及你为什么选择这个岗位。",
            "分享一个你最有代表性的项目：你负责什么，解决了什么问题？",
            "项目里遇到的最大技术或协作困难是什么？你如何处理？",
            "如果重新做这个项目，你会优先改进什么？为什么？",
            "你希望在未来一年掌握哪些能力？会如何安排学习？");
    public static final Set<String> KINDS = Set.of("项目与经历", "表达与结构", "职业动机", "技术", "行为", "HR", "综合");
    private final JdbcTemplate jdbc;

    private final com.offerpilot.common.artifact.JobArtifacts artifacts;
    private final InterviewFollowUps followUps;
    public InterviewService(JdbcTemplate jdbc, com.offerpilot.common.artifact.JobArtifacts artifacts, InterviewFollowUps followUps) {
        this.jdbc = jdbc; this.artifacts=artifacts; this.followUps = followUps;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private InterviewResumeSource resumes;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
    private InterviewOptions readOptions(String value) {
        try { return value == null ? null : json.readValue(value, InterviewOptions.class); }
        catch (Exception e) { throw new IllegalStateException("Invalid stored interview options", e); }
    }
    private String writeOptions(InterviewOptions options) {
        try { return options == null ? null : json.writeValueAsString(options); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public record Summary(String id, String role, String kind, int questionIndex, boolean completed,
                          String createdAt, String updatedAt, String jobId, InterviewOptions options) {
        public Summary(String id, String role, String kind, int questionIndex, boolean completed, String createdAt, String updatedAt, String jobId) {
            this(id, role, kind, questionIndex, completed, createdAt, updatedAt, jobId, null);
        }
        public Summary(String id, String role, String kind, int questionIndex, boolean completed, String createdAt, String updatedAt) {
            this(id, role, kind, questionIndex, completed, createdAt, updatedAt, null);
        }
    }
    public record Answer(int questionIndex, String questionText, String answerText,
                         String followUpQuestion, String followUpAnswer, String followUpSource,
                         String questionType, String difficulty, String questionSource, com.fasterxml.jackson.databind.JsonNode evaluation) {
        public Answer(int index, String question, String answer, String followUp, String more, String source) {
            this(index,question,answer,followUp,more,source,null,null,null,null);
        }
        public Answer(int questionIndex, String questionText, String answerText) {
            this(questionIndex, questionText, answerText, null, null, null);
        }
        /** Main answer plus the follow-up answer: what the rubric and STAR detection should see. */
        public String fullText() {
            String main = answerText == null ? "" : answerText.strip();
            String more = followUpAnswer == null ? "" : followUpAnswer.strip();
            return more.isEmpty() ? main : main.isEmpty() ? more : main + "\n" + more;
        }
    }
    public record Session(String id, String role, String kind, int questionIndex, boolean completed,
                          String createdAt, String updatedAt, List<Answer> answers, String jobId, InterviewOptions options, String generationSource) {
        public Session(String id, String role, String kind, int questionIndex, boolean completed, String createdAt, String updatedAt,
                       List<Answer> answers, String jobId, InterviewOptions options) {
            this(id,role,kind,questionIndex,completed,createdAt,updatedAt,answers,jobId,options,null);
        }
        public Session(String id, String role, String kind, int questionIndex, boolean completed, String createdAt, String updatedAt, List<Answer> answers, String jobId) {
            this(id, role, kind, questionIndex, completed, createdAt, updatedAt, answers, jobId, null);
        }
        public Session(String id, String role, String kind, int questionIndex, boolean completed,
                       String createdAt, String updatedAt, List<Answer> answers) {
            this(id, role, kind, questionIndex, completed, createdAt, updatedAt, answers, null);
        }
    }

    private Summary summary(ResultSet rs, int row) throws SQLException {
        return new Summary(rs.getString("id"), rs.getString("role"), rs.getString("kind"),
                rs.getInt("question_index"), rs.getBoolean("completed"),
                rs.getTimestamp("created_at").toInstant().toString(),
                rs.getTimestamp("updated_at").toInstant().toString(), rs.getString("job_id"), readOptions(rs.getString("options")));
    }

    @Transactional(readOnly = true)
    public List<Summary> list(String owner) {
        return jdbc.query("SELECT * FROM interview_sessions WHERE user_id=? ORDER BY created_at DESC,id",
                this::summary, owner);
    }

    /** Latest completed owned practice; role is displayed as provenance, never inferred as a job link. */
    @Transactional(readOnly = true)
    public Session latestCompleted(String owner) {
        var ids = jdbc.queryForList("""
                SELECT id FROM interview_sessions WHERE user_id=? AND completed=true
                ORDER BY updated_at DESC,id DESC LIMIT 1
                """, String.class, owner);
        return ids.isEmpty() ? null : get(owner, ids.get(0));
    }

    @Transactional
    public Session create(String owner, String role, String kind) {
        return create(owner,role,kind,null);
    }

    @Transactional
    public Session create(String owner, String role, String kind, String jobId) {
        return create(owner, role, kind, jobId, null);
    }

    @Transactional
    public Session create(String owner, String role, String kind, String jobId, InterviewOptions options) {
        return create(owner,role,kind,jobId,options,false);
    }

    @Transactional
    public Session create(String owner, String role, String kind, String jobId, InterviewOptions options, boolean redesigned) {
        if (role == null || role.isBlank() || role.codePointCount(0, role.length()) > 100
                || kind == null || !KINDS.contains(kind)) invalid();
        if (options == null && Set.of("技术", "行为", "HR", "综合").contains(kind)) options = InterviewOptions.defaults();
        if (options != null) options.validate();
        String context = options == null || options.resumeId() == null ? null
                : resumes.context(owner, options.resumeId(), options.versionId());
        artifacts.lockJob(owner,jobId);
        String jobContext = options == null ? null : artifacts.interviewContext(owner, jobId);
        var questions = redesigned ? options.fallbackQuestions(role.strip()) : options == null ? QUESTIONS : options.questions(role.strip(), kind, context).stream()
                .map(question -> jobContext == null || jobContext.isBlank() ? question : question + "参考所选岗位要求：「" + jobContext + "」。").toList();
        String id = IdGenerator.next("iv");
        jdbc.update("INSERT INTO interview_sessions(id,user_id,role,kind,job_id,options) VALUES (?,?,?,?,?,?::jsonb)",
                id, owner, role.strip(), kind, jobId, writeOptions(options));
        if (redesigned) jdbc.update("UPDATE interview_sessions SET generation_source='pending',resume_context=?,job_context=? WHERE id=? AND user_id=?",
                options.resumeId()==null ? "" : resumes.evidence(owner,options.resumeId(),options.versionId()), jobContext, id, owner);
        for (int index = 0; index < questions.size(); index++) {
            jdbc.update("INSERT INTO interview_answers(session_id,question_index,question_text) VALUES (?,?,?)",
                    id, index, questions.get(index));
        }
        if (redesigned) for (int index=0; index<questions.size(); index++) jdbc.update(
                "UPDATE interview_answers SET question_type=?,difficulty=?,question_source='rule' WHERE session_id=? AND question_index=?",
                options.questionTypes().get(index),options.difficulty(),id,index);
        artifacts.linkJob(owner,jobId);
        if(notices!=null) notices.send(owner,"INTERVIEW_PLAN","interview-created:"+id,"面试练习计划已创建",
            "面向「"+role.strip()+"」的练习已保存，可继续作答。","/interviews/"+id);
        return get(owner, id);
    }

    public void linkStories(String owner, String sessionId) { artifacts.linkSession(owner,sessionId); }

    @Transactional(readOnly = true)
    public Session get(String owner, String id) {
        var rows = jdbc.query("SELECT * FROM interview_sessions WHERE id=? AND user_id=?", this::summary, id, owner);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.INTERVIEW_NOT_FOUND);
        var session = rows.get(0);
        var answers = jdbc.query("""
                SELECT a.*
                FROM interview_answers a JOIN interview_sessions s ON s.id=a.session_id
                WHERE s.id=? AND s.user_id=? ORDER BY a.question_index
                """, (rs, row) -> new Answer(rs.getInt("question_index"), rs.getString("question_text"),
                rs.getString("answer_text"), rs.getString("follow_up_question"), rs.getString("follow_up_answer"),
                rs.getString("follow_up_source"),rs.getString("question_type"),rs.getString("difficulty"),rs.getString("question_source"),
                readJson(rs.getString("evaluation"))), id, owner);
        return new Session(session.id(), session.role(), session.kind(), session.questionIndex(), session.completed(),
                session.createdAt(), session.updatedAt(), answers, session.jobId(), session.options(), jdbc.queryForObject("SELECT generation_source FROM interview_sessions WHERE id=? AND user_id=?",String.class,id,owner));
    }

    @Transactional
    public Session update(String owner, String id, Integer questionIndex, Boolean completed) {
        if (questionIndex == null && completed == null) invalid();
        if (questionIndex != null) validateIndex(owner, id, questionIndex);
        lock(owner, id);
        jdbc.update("""
                UPDATE interview_sessions SET question_index=COALESCE(?,question_index),
                completed=COALESCE(?,completed),revision=revision+1,report=NULL,updated_at=now() WHERE id=? AND user_id=?
                """, questionIndex, completed, id, owner);
        return get(owner, id);
    }

    @Transactional
    public Session saveAnswer(String owner, String id, int index, String answerText) {
        if (answerText == null || answerText.codePointCount(0, answerText.length()) > 12000)
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请提供 answerText，最多12000个字符，可为空字符串。");
        validateIndex(owner, id, index);
        lock(owner, id);
        // An unanswered follow-up was asked about the old text; drop it so the next one fits the new answer.
        jdbc.update("""
                UPDATE interview_answers SET evaluation=CASE WHEN answer_text IS DISTINCT FROM ? THEN NULL ELSE evaluation END,answer_text=?,
                follow_up_question=CASE WHEN answer_text IS DISTINCT FROM ? AND COALESCE(follow_up_answer,'')='' THEN NULL ELSE follow_up_question END,
                follow_up_source=CASE WHEN answer_text IS DISTINCT FROM ? AND COALESCE(follow_up_answer,'')='' THEN NULL ELSE follow_up_source END
                WHERE session_id=? AND question_index=?
                AND EXISTS (SELECT 1 FROM interview_sessions WHERE id=? AND user_id=?)
                """, answerText.strip(), answerText.strip(), answerText.strip(), answerText.strip(), id, index, id, owner);
        jdbc.update("UPDATE interview_sessions SET updated_at=now(),revision=revision+1,report=NULL WHERE id=? AND user_id=?", id, owner);
        return get(owner, id);
    }

    /**
     * Ask (once) the interviewer follow-up for a saved main answer. The model call holds no transaction
     * or row lock; the write only lands if the answer is unchanged and no follow-up exists yet.
     */
    public Session followUp(String owner, String id, int index) {
        validateIndex(owner, id, index);
        var session = get(owner, id);
        if (session.completed()) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "练习已完成，不能再生成追问。");
        var answer = session.answers().get(index);
        if (answer.followUpQuestion() != null) return session;
        String text = answer.answerText() == null ? "" : answer.answerText().strip();
        if (text.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请先保存这道题的回答，再请面试官追问。");
        var generated = followUps.generate(owner, session.role(), answer.questionText(), text);
        com.offerpilot.common.artifact.BackgroundWork.check();
        jdbc.update("""
                UPDATE interview_answers SET follow_up_question=?,follow_up_source=?
                WHERE session_id=? AND question_index=? AND follow_up_question IS NULL AND answer_text=?
                AND EXISTS (SELECT 1 FROM interview_sessions WHERE id=? AND user_id=?)
                """, generated.question(), generated.source(), id, index, text, id, owner);
        return get(owner, id);
    }

    @Transactional
    public Session saveFollowUpAnswer(String owner, String id, int index, String answerText) {
        if (answerText == null || answerText.codePointCount(0, answerText.length()) > 12000)
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请提供 answerText，最多12000个字符，可为空字符串。");
        validateIndex(owner, id, index);
        lock(owner, id);
        int updated = jdbc.update("""
                UPDATE interview_answers SET evaluation=CASE WHEN follow_up_answer IS DISTINCT FROM ? THEN NULL ELSE evaluation END,follow_up_answer=? WHERE session_id=? AND question_index=?
                AND follow_up_question IS NOT NULL
                """, answerText.strip(), answerText.strip(), id, index);
        if (updated == 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "这道题还没有追问。");
        jdbc.update("UPDATE interview_sessions SET updated_at=now(),revision=revision+1,report=NULL WHERE id=? AND user_id=?", id, owner);
        return get(owner, id);
    }

    @Transactional
    public Session getForUpdate(String owner, String id) {
        lock(owner, id);
        return get(owner, id);
    }

    // Serialize answer/progress writes on the owned session; never lock another owner's resource.
    private void lock(String owner, String id) {
        var rows = jdbc.queryForList("SELECT id FROM interview_sessions WHERE id=? AND user_id=? FOR UPDATE",
                String.class, id, owner);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.INTERVIEW_NOT_FOUND);
    }

    private void validateIndex(String owner, String id, int index) {
        if (index < 0 || index >= 15 || index >= get(owner, id).answers().size())
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "题目序号超出本次面试范围。");
    }

    public com.fasterxml.jackson.databind.JsonNode readJson(String value) {
        try { return value==null ? null : json.readTree(value); }
        catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
    }
    public record MetricSample(
            @jakarta.validation.constraints.NotNull java.time.Instant ts,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.DecimalMin("0") @jakarta.validation.constraints.DecimalMax("1") Double eyeContact,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.DecimalMin("0") @jakarta.validation.constraints.DecimalMax("1") Double smile,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.DecimalMin("0") @jakarta.validation.constraints.DecimalMax("1") Double headStability,
            @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.DecimalMin("0") @jakarta.validation.constraints.DecimalMax("1") Double postureStability) {}
    public record MetricWindow(String id, int samples) {}

    @Transactional
    public MetricWindow postMetrics(String owner, String id, List<MetricSample> samples) {
        if (samples == null || samples.isEmpty() || samples.size() > 240) invalid();
        for (var sample : samples) {
            if (sample == null || sample.ts() == null) invalid();
            for (Double value : new Double[]{sample.eyeContact(), sample.smile(), sample.headStability(), sample.postureStability()})
                if (value == null || !Double.isFinite(value) || value < 0 || value > 1) invalid();
        }
        lock(owner, id);
        var start = samples.stream().map(MetricSample::ts).min(java.time.Instant::compareTo).orElseThrow();
        var end = samples.stream().map(MetricSample::ts).max(java.time.Instant::compareTo).orElseThrow();
        String windowId = IdGenerator.next("im");
        jdbc.update("""
                INSERT INTO interview_metrics(id,session_id,window_start,window_end,eye_contact,smile,head_stability,posture_stability,samples)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, windowId, id, java.sql.Timestamp.from(start), java.sql.Timestamp.from(end),
                samples.stream().mapToDouble(MetricSample::eyeContact).average().orElseThrow(),
                samples.stream().mapToDouble(MetricSample::smile).average().orElseThrow(),
                samples.stream().mapToDouble(MetricSample::headStability).average().orElseThrow(),
                samples.stream().mapToDouble(MetricSample::postureStability).average().orElseThrow(), samples.size());
        return new MetricWindow(windowId, samples.size());
    }

    /** Overlay live numeric evidence on both legacy and stored model reports, without invalidating text scores. */
    public InterviewRubric.Report withPresence(String owner, String id, InterviewRubric.Report report) {
        var values = jdbc.query("""
                SELECT sum(m.samples) AS samples,
                       sum(m.eye_contact*m.samples)/sum(m.samples) AS eye_contact,
                       sum(m.smile*m.samples)/sum(m.samples) AS smile,
                       sum(m.head_stability*m.samples)/sum(m.samples) AS head_stability,
                       sum(m.posture_stability*m.samples)/sum(m.samples) AS posture_stability
                FROM interview_metrics m JOIN interview_sessions s ON s.id=m.session_id
                WHERE s.id=? AND s.user_id=?
                HAVING sum(m.samples)>0
                """, (rs, row) -> new InterviewRubric.Presence(rs.getDouble("eye_contact"), rs.getDouble("smile"),
                rs.getDouble("head_stability"), rs.getDouble("posture_stability"), "来自浏览器端分析，仅供参考"), id, owner);
        return report.withPresence(values.isEmpty() ? null : values.get(0));
    }
    public InterviewRubric.Report report(String owner,String id) {
        var session=get(owner,id);
        String stored=jdbc.queryForObject("SELECT report::text FROM interview_sessions WHERE id=? AND user_id=?",String.class,id,owner);
        if (stored!=null) try { return withPresence(owner,id,json.readValue(stored,InterviewRubric.Report.class)); }
            catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
        if (session.generationSource()==null) return withPresence(owner,id,InterviewRubric.score(session));
        return withPresence(owner,id,new InterviewRubric.Report(id,null,new InterviewRubric.Dimensions(null,null,null,null),List.of(),List.of(),List.of(),List.of(),List.of(),
                "尚未生成报告；请从后台任务生成。未评分题不产生分数。"));
    }
    private void invalid() { throw new BusinessException(ErrorCode.VALIDATION_FAILED); }
}
