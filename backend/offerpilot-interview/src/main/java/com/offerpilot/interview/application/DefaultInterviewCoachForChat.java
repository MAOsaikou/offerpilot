package com.offerpilot.interview.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultInterviewCoachForChat implements InterviewCoachForChat {
    private final InterviewService sessions;
    public DefaultInterviewCoachForChat(InterviewService sessions) { this.sessions = sessions; }
    public InterviewService.Session session(String owner, String id) { return sessions.get(owner, id); }
    public String resolveSession(String owner, String id, boolean activeOnly) {
        if (id != null && !id.isBlank()) return sessions.get(owner, id).id();
        return sessions.list(owner).stream().filter(s -> !activeOnly || !s.completed()).findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_NOT_FOUND, "没有可用的面试会话，请先开始文字模拟面试。")).id();
    }
    @Transactional
    public Turn startSession(String owner, String role, String kind) {
        return turn(sessions.create(owner, role, kind), "面试已创建，请回答第一题。继续时会先确认保存答案。");
    }
    @Transactional
    public Turn startSession(String owner, String role, String kind, String jobId) {
        return turn(sessions.create(owner,role,kind,jobId), "面试已创建，请回答第一题。继续时会先确认保存答案。");
    }
    @Transactional
    public Turn continueSession(String owner, String id, String answer, boolean nextQuestion, String expectedUpdatedAt) {
        var session = sessions.getForUpdate(owner, id);
        checkSnapshot(session, expectedUpdatedAt);
        if (session.completed()) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "面试已完成，请生成评分卡或开始新面试。");
        int index = session.questionIndex();
        String previous = session.answers().get(index).answerText();
        boolean supplement = previous != null && !previous.isBlank();
        if (!nextQuestion) {
            if (answer == null || answer.isBlank()) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "请提供本题答案。");
            String combined = supplement ? previous + "\n" + answer : answer;
            session = sessions.saveAnswer(owner, id, index, combined);
            var missing = InterviewRubric.detect(combined).missing();
            // One clarification per question; subsequent supplement advances even if still incomplete.
            if (!supplement && !missing.isEmpty()) return new Turn(id, "ASKING", index, session.answers().size(),
                    missing.get(0), "答案已保存。请补充上述内容，或发送「下一题」跳过追问。");
        }
        if (index == session.answers().size() - 1)
            return new Turn(id, "CLOSING", index, session.answers().size(), "本次面试问题已结束，请生成评分卡。", "未自动标记完成；可请求「结束面试」并批准。");
        return turn(sessions.update(owner, id, index + 1, null), "已进入下一题。");
    }
    public InterviewRubric.Report scoreSession(String owner, String id) { return InterviewRubric.score(sessions.get(owner, id)); }
    @Transactional
    public InterviewRubric.Report completeSession(String owner, String id, String expectedUpdatedAt) {
        checkSnapshot(sessions.getForUpdate(owner, id), expectedUpdatedAt);
        return InterviewRubric.score(sessions.update(owner, id, null, true));
    }
    private void checkSnapshot(InterviewService.Session session, String expectedUpdatedAt) {
        if (!session.updatedAt().equals(expectedUpdatedAt)) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "面试已被更新，请重新提交并确认，避免覆盖新答案。");
    }
    private Turn turn(InterviewService.Session s, String note) {
        return new Turn(s.id(), "LISTENING", s.questionIndex(), s.answers().size(),
                s.answers().get(s.questionIndex()).questionText(), note);
    }
}
