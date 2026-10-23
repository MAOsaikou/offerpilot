package com.offerpilot.interview;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.error.GlobalExceptionHandler;
import com.offerpilot.interview.application.InterviewRecapExporter;
import com.offerpilot.interview.application.InterviewService;
import com.offerpilot.interview.web.InterviewController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Service SQL boundaries and real MVC/advice without database, sockets or Docker. */
class InterviewServiceTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final InterviewService service = new InterviewService(jdbc, mock(com.offerpilot.common.artifact.JobArtifacts.class), mock(com.offerpilot.interview.application.InterviewFollowUps.class));
    final String owner = "owner-a";
    final String id = "iv_1";
    final InterviewService.Summary summary = new InterviewService.Summary(id, "工程师", "项目与经历", 0, false, "2026-10-09T00:00:00Z", "2026-10-09T00:00:00Z");
    final List<InterviewService.Answer> answers = java.util.stream.IntStream.range(0, 5)
            .mapToObj(index -> new InterviewService.Answer(index, InterviewService.QUESTIONS.get(index), "")).toList();
    MockMvc mvc;

    @BeforeEach
    void setup() {
        CurrentUser users = mock(CurrentUser.class);
        when(users.require("Bearer a")).thenReturn(owner);
        when(users.require("Bearer b")).thenReturn("owner-b");
        when(users.require(null)).thenThrow(new BusinessException(ErrorCode.UNAUTHENTICATED));
        mvc = MockMvcBuilders.standaloneSetup(new InterviewController(service, new InterviewRecapExporter(service), users))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @SuppressWarnings("unchecked")
    void stubDetail() {
        doReturn(List.of(summary)).when(jdbc).query(eq("SELECT * FROM interview_sessions WHERE id=? AND user_id=?"),
                any(RowMapper.class), eq(id), eq(owner));
        doReturn(answers).when(jdbc).query(contains("FROM interview_answers a JOIN interview_sessions s"), any(RowMapper.class), eq(id), eq(owner));
        when(jdbc.queryForList(contains("FOR UPDATE"), eq(String.class), eq(id), eq(owner))).thenReturn(List.of(id));
    }



    @Test
    @SuppressWarnings("unchecked")
    void reportsOnlyExposePresenceWhenNumericEvidenceExistsIncludingStoredReports() throws Exception {
        stubDetail();
        var report = service.report(owner, id);
        assertThat(report.presence()).isNull();
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(json.valueToTree(report).has("presence")).isFalse();
        assertThat(json.valueToTree(report).path("dimensions").has("presence")).isFalse();
        var presence = new com.offerpilot.interview.application.InterviewRubric.Presence(.8, .2, .9, .7, "来自浏览器端分析，仅供参考");
        doReturn(List.of(presence)).when(jdbc).query(contains("FROM interview_metrics m"), any(RowMapper.class), eq(id), eq(owner));
        assertThat(service.report(owner, id).presence()).isEqualTo(presence);
        when(jdbc.queryForObject(contains("SELECT report::text"), eq(String.class), eq(id), eq(owner)))
                .thenReturn(json.writeValueAsString(report));
        assertThat(service.report(owner, id).presence()).isEqualTo(presence);
    }

    @Test
    void metricsAreValidatedAggregatedAndOwnerChecked() throws Exception {
        var first = new InterviewService.MetricSample(java.time.Instant.parse("2026-10-23T00:00:00Z"), .2, .4, .6, .8);
        var second = new InterviewService.MetricSample(java.time.Instant.parse("2026-10-23T00:00:01Z"), .8, .6, .4, .2);
        assertInvalid(() -> service.postMetrics(owner, id, List.of()));
        assertInvalid(() -> service.postMetrics(owner, id, List.of(new InterviewService.MetricSample(first.ts(), Double.NaN, .4, .6, .8))));
        stubDetail();
        assertThat(service.postMetrics(owner, id, List.of(second, first)).samples()).isEqualTo(2);
        verify(jdbc).update(contains("INSERT INTO interview_metrics"), anyString(), eq(id),
                eq(java.sql.Timestamp.from(first.ts())), eq(java.sql.Timestamp.from(second.ts())), eq(.5), eq(.5), eq(.5), eq(.5), eq(2));
        mvc.perform(post("/api/v1/interviews/"+id+"/metrics").header("Authorization", "Bearer a")
                .contentType("application/json").content("{\"samples\":[{\"ts\":\"2026-10-23T00:00:00Z\",\"eyeContact\":0.8,\"smile\":0.2,\"headStability\":0.9,\"postureStability\":0.5}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.samples").value(1));
        mvc.perform(post("/api/v1/interviews/"+id+"/metrics").header("Authorization", "Bearer b")
                .contentType("application/json").content("{\"samples\":[{\"ts\":\"2026-10-23T00:00:00Z\",\"eyeContact\":0.8,\"smile\":0.2,\"headStability\":0.9,\"postureStability\":0.5}]}"))
                .andExpect(status().isNotFound());
        for (String body : List.of("{}", "{\"samples\":[]}", "{\"samples\":[null]}",
                "{\"samples\":[{\"ts\":\"invalid\"}]}",
                "{\"samples\":[{\"ts\":\"2026-10-23T00:00:00Z\",\"eyeContact\":1.1,\"smile\":0.2,\"headStability\":0.9,\"postureStability\":0.5}]}")) {
            mvc.perform(post("/api/v1/interviews/"+id+"/metrics").header("Authorization", "Bearer b")
                    .contentType("application/json").content(body)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void missingOrOtherOwnerIs404ForGetProgressAndAnswersWithoutWrites() throws Exception {
        for (String target : List.of(id, "missing")) {
            mvc.perform(get("/api/v1/interviews/" + target).header("Authorization", "Bearer b"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(30701));
            mvc.perform(patch("/api/v1/interviews/" + target).header("Authorization", "Bearer b")
                    .contentType("application/json").content("{\"completed\":true}"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(30701));
            mvc.perform(put("/api/v1/interviews/" + target + "/answers/0").header("Authorization", "Bearer b")
                    .contentType("application/json").content("{\"answerText\":\"越权\"}"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value(30701));
        }
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void validatesServiceInputsBeforeAnyDatabaseAccess() {
        assertInvalid(() -> service.create(owner, "a".repeat(101), "项目与经历"));
        assertInvalid(() -> service.create(owner, " ", "项目与经历"));
        assertInvalid(() -> service.create(owner, "工程师", "其他"));
        assertInvalid(() -> service.saveAnswer(owner, id, 0, "😀".repeat(12001)));
        assertInvalid(() -> service.saveAnswer(owner, id, 0, null));
        for (int index : new int[] {-1, 15}) {
            assertInvalid(() -> service.saveAnswer(owner, id, index, ""));
            assertInvalid(() -> service.update(owner, id, index, null));
        }
        assertInvalid(() -> service.update(owner, id, null, null));
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsInvalidAnswerIndexAndEmptyProgressAs400OverHttp() throws Exception {
        for (String index : new String[] {"-1", "15", "abc", "2147483648"}) {
            mvc.perform(put("/api/v1/interviews/iv_1/answers/" + index).header("Authorization", "Bearer b")
                    .contentType("application/json").content("{\"answerText\":\"\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(10001));
        }
        mvc.perform(patch("/api/v1/interviews/iv_1").header("Authorization", "Bearer b")
                .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(10001));
        verifyNoInteractions(jdbc);
    }

    @Test
    void authenticationIsRequiredBeforeDatabaseAccess() throws Exception {
        mvc.perform(get("/api/v1/interviews")).andExpect(status().isUnauthorized());
        verifyNoInteractions(jdbc);
    }

    private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }
}
