package com.offerpilot.interview;

import com.offerpilot.interview.application.*;
import com.offerpilot.common.error.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InterviewCoachTest {
    final InterviewService service = mock(InterviewService.class);
    final DefaultInterviewCoachForChat coach = new DefaultInterviewCoachForChat(service);
    static InterviewService.Session session(int index, String answer, boolean completed) {
        return new InterviewService.Session("iv_1", "Java工程师", "项目与经历", index, completed, "created", "snapshot",
                IntStream.range(0, 5).mapToObj(i -> new InterviewService.Answer(i, InterviewService.QUESTIONS.get(i), i == index ? answer : "")).toList());
    }
    @Test void chineseAndEnglishStarEvidenceIsDeterministicAndEmptyHasNoSignals() {
        assertThat(InterviewRubric.detect("当时在项目中，我负责性能目标。我采用缓存，最终降低延迟20%。")).isEqualTo(new InterviewRubric.Star(true, true, true, true));
        assertThat(InterviewRubric.detect("During a project I was responsible for a goal, designed tests and improved performance.").count()).isEqualTo(4);
        assertThat(InterviewRubric.detect("我很优秀").missing()).hasSize(4);
        assertThat(InterviewRubric.detect(null).count()).isZero();
    }
    @Test void reportHasNamedDimensionsAndSnapshotDoesNotCompleteOrInventPresence() throws Exception {
        var s = session(0, "当时我负责Java工程师接口目标。我实现缓存，最终降低延迟20%。", false);
        when(service.get("alice", "iv_1")).thenReturn(s);
        var report = coach.scoreSession("alice", "iv_1");
        assertThat(report).isEqualTo(coach.scoreSession("alice", "iv_1"));
        assertThat(report.dimensions().professional()).isGreaterThan(0);
        assertThat(report.overall()).isEqualTo((report.dimensions().professional() + report.dimensions().expression() + report.dimensions().logic() + report.dimensions().jobFit()) / 4);
        assertThat(report.turns()).hasSize(5);
        assertThat(report.turns().get(1).score()).isZero();
        assertThat(report.turns().get(1).betterAnswerOutline()).hasSize(5);
        assertThat(report.weaknesses()).isNotEmpty();
        var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(report);
        assertThat(json.path("dimensions").has("presence")).isFalse();
        assertThat(json.has("presence")).isFalse();
        assertThat(report.note()).contains("未标记完成", "端侧表现分析未启用");
        verify(service, never()).update(any(), any(), any(), any());
    }
    @Test void startUsesExistingServiceAndThinAnswerGetsOneClarificationThenAdvances() {
        var initial = session(0, "", false);
        when(service.create("alice", "Java工程师", "项目与经历")).thenReturn(initial);
        assertThat(coach.startSession("alice", "Java工程师", "项目与经历").question()).isEqualTo(InterviewService.QUESTIONS.get(0));
        when(service.getForUpdate("alice", "iv_1")).thenReturn(initial);
        when(service.saveAnswer("alice", "iv_1", 0, "我负责接口")).thenReturn(session(0, "我负责接口", false));
        var followup = coach.continueSession("alice", "iv_1", "我负责接口", false, "snapshot");
        assertThat(followup.state()).isEqualTo("ASKING");
        assertThat(followup.question()).contains("背景");
        verify(service, never()).update(any(), any(), any(), any());
        when(service.getForUpdate("alice", "iv_1")).thenReturn(session(0, "我负责接口", false));
        when(service.saveAnswer("alice", "iv_1", 0, "我负责接口\n当时是课程项目")).thenReturn(session(0, "我负责接口\n当时是课程项目", false));
        when(service.update("alice", "iv_1", 1, null)).thenReturn(session(1, "", false));
        assertThat(coach.continueSession("alice", "iv_1", "当时是课程项目", false, "snapshot").questionIndex()).isEqualTo(1);
        verify(service).saveAnswer("alice", "iv_1", 0, "我负责接口\n当时是课程项目");

        clearInvocations(service);
        when(service.getForUpdate("alice", "iv_1")).thenReturn(session(4, "已有答案", false));
        assertThat(coach.continueSession("alice", "iv_1", "下一题", true, "snapshot").state()).isEqualTo("CLOSING");
        verify(service, never()).saveAnswer(any(), any(), anyInt(), any());
        verify(service, never()).update(any(), any(), any(), any());

    }

    @Test void staleApprovalCompletedSessionAndOtherOwnerCannotWrite() {
        when(service.getForUpdate("alice", "iv_1")).thenReturn(session(0, "", false));
        assertThatThrownBy(() -> coach.continueSession("alice", "iv_1", "答案", false, "old")).hasMessageContaining("已被更新");
        when(service.getForUpdate("alice", "iv_1")).thenReturn(session(0, "", true));
        assertThatThrownBy(() -> coach.continueSession("alice", "iv_1", "答案", false, "snapshot")).hasMessageContaining("已完成");
        when(service.get("bob", "iv_1")).thenThrow(new BusinessException(ErrorCode.INTERVIEW_NOT_FOUND));
        assertThatThrownBy(() -> coach.scoreSession("bob", "iv_1")).isInstanceOf(BusinessException.class);
        verify(service, never()).saveAnswer(any(), any(), anyInt(), any());
    }
    @Test void sessionResolutionOnlyListsTrustedOwnersAndCompletingDelegatesToCrud() {
        when(service.list("alice")).thenReturn(List.of(new InterviewService.Summary("done", "Java", "项目与经历", 4, true, "", ""),
                new InterviewService.Summary("active", "Java", "项目与经历", 1, false, "", "")));
        assertThat(coach.resolveSession("alice", null, false)).isEqualTo("done");
        assertThat(coach.resolveSession("alice", null, true)).isEqualTo("active");
        when(service.getForUpdate("alice", "iv_1")).thenReturn(session(4, "", false));
        when(service.update("alice", "iv_1", null, true)).thenReturn(session(4, "", true));
        assertThat(coach.completeSession("alice", "iv_1", "snapshot").note()).contains("已完成会话");
        verify(service).update("alice", "iv_1", null, true);
    }
}
