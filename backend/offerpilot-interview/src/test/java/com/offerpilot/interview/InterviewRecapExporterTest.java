package com.offerpilot.interview;

import com.offerpilot.interview.application.InterviewRecapExporter;
import com.offerpilot.interview.application.InterviewService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterviewRecapExporterTest {
    @Mock InterviewService sessions;

    @Test
    void markdownIncludesStarDimensionsAndFullAnswers() {
        var answers = List.of(
                new InterviewService.Answer(0, "q1", "当时在项目中负责排查，我通过日志定位最终降低了延迟 20%"),
                new InterviewService.Answer(1, "q2", "")
        );
        when(sessions.get("alice", "iv_1")).thenReturn(
                new InterviewService.Session("iv_1", "Java 后端", "项目与经历", 1, true,
                        "2026-10-10T00:00:00Z", "2026-10-10T00:00:02Z", answers));
        String md = new InterviewRecapExporter(sessions).markdown("alice", "iv_1");
        assertThat(md).contains("面试复盘包", "综合线索分", "STAR 线索", "S 背景", "原始完整回答", "降低了延迟 20%", "未作答");
        assertThat(md).contains("文字规则评分");
    }
}
