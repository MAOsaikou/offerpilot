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
    public static final List<String> QUESTIONS = List.of(
            "请用两分钟介绍你自己，以及你为什么选择这个岗位。",
            "分享一个你最有代表性的项目：你负责什么，解决了什么问题？",
            "项目里遇到的最大技术或协作困难是什么？你如何处理？",
            "如果重新做这个项目，你会优先改进什么？为什么？",
            "你希望在未来一年掌握哪些能力？会如何安排学习？");
    public static final Set<String> KINDS = Set.of("项目与经历", "表达与结构", "职业动机");
    private final JdbcTemplate jdbc;

    public InterviewService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Summary(String id, String role, String kind, int questionIndex, boolean completed,
                          String createdAt, String updatedAt) {}
    public record Answer(int questionIndex, String questionText, String answerText) {}
    public record Session(String id, String role, String kind, int questionIndex, boolean completed,
                          String createdAt, String updatedAt, List<Answer> answers) {}

    private Summary summary(ResultSet rs, int row) throws SQLException {
        return new Summary(rs.getString("id"), rs.getString("role"), rs.getString("kind"),
                rs.getInt("question_index"), rs.getBoolean("completed"),
                rs.getTimestamp("created_at").toInstant().toString(),
                rs.getTimestamp("updated_at").toInstant().toString());
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
        if (role == null || role.isBlank() || role.codePointCount(0, role.length()) > 100
                || kind == null || !KINDS.contains(kind)) invalid();
        String id = IdGenerator.next("iv");
        jdbc.update("INSERT INTO interview_sessions(id,user_id,role,kind) VALUES (?,?,?,?)",
                id, owner, role.strip(), kind);
        for (int index = 0; index < QUESTIONS.size(); index++) {
            jdbc.update("INSERT INTO interview_answers(session_id,question_index,question_text) VALUES (?,?,?)",
                    id, index, QUESTIONS.get(index));
        }
        return get(owner, id);
    }

    @Transactional(readOnly = true)
    public Session get(String owner, String id) {
        var rows = jdbc.query("SELECT * FROM interview_sessions WHERE id=? AND user_id=?", this::summary, id, owner);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.INTERVIEW_NOT_FOUND);
        var session = rows.get(0);
        var answers = jdbc.query("""
                SELECT a.question_index,a.question_text,a.answer_text FROM interview_answers a
                JOIN interview_sessions s ON s.id=a.session_id
                WHERE s.id=? AND s.user_id=? ORDER BY a.question_index
                """, (rs, row) -> new Answer(rs.getInt("question_index"), rs.getString("question_text"),
                rs.getString("answer_text")), id, owner);
        return new Session(session.id(), session.role(), session.kind(), session.questionIndex(), session.completed(),
                session.createdAt(), session.updatedAt(), answers);
    }

    @Transactional
    public Session update(String owner, String id, Integer questionIndex, Boolean completed) {
        if (questionIndex == null && completed == null) invalid();
        if (questionIndex != null) validateIndex(questionIndex);
        lock(owner, id);
        jdbc.update("""
                UPDATE interview_sessions SET question_index=COALESCE(?,question_index),
                completed=COALESCE(?,completed),updated_at=now() WHERE id=? AND user_id=?
                """, questionIndex, completed, id, owner);
        return get(owner, id);
    }

    @Transactional
    public Session saveAnswer(String owner, String id, int index, String answerText) {
        validateIndex(index);
        if (answerText == null || answerText.codePointCount(0, answerText.length()) > 12000)
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请提供 answerText，最多12000个字符，可为空字符串。");
        lock(owner, id);
        jdbc.update("""
                UPDATE interview_answers SET answer_text=? WHERE session_id=? AND question_index=?
                AND EXISTS (SELECT 1 FROM interview_sessions WHERE id=? AND user_id=?)
                """, answerText.strip(), id, index, id, owner);
        jdbc.update("UPDATE interview_sessions SET updated_at=now() WHERE id=? AND user_id=?", id, owner);
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

    private void validateIndex(int index) {
        if (index < 0 || index >= QUESTIONS.size())
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "题目序号必须为0到4。");
    }

    private void invalid() { throw new BusinessException(ErrorCode.VALIDATION_FAILED); }
}
