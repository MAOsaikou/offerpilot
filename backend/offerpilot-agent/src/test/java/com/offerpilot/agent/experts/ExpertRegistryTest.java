package com.offerpilot.agent.experts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.agent.domain.ExpertType;
import com.offerpilot.agent.domain.PlanStep;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.job.application.JobMatchCards;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpertRegistryTest {
    @Test void interviewerNeverReturnsPlaceholderAndWritesCannotBypassApproval() {
        var score = new PlanStep("s1", "评分", ExpertType.INTERVIEWER, Map.of("action", "scoreInterview"), List.of());
        assertThat(new ExpertRegistry().execute("owner", Map.of(), "run", score, Map.of()).block().summary())
                .contains("暂不可用").doesNotContain("占位专家");
        var write = new PlanStep("s1", "开始", ExpertType.INTERVIEWER, Map.of("action", "startInterview"), List.of());
        assertThatThrownBy(() -> new ExpertRegistry().execute("owner", Map.of(), "run", write, Map.of()).block())
                .hasMessageContaining("必须先审批");
    }
    @Test void careerReturnsRealCardOrClearGuidanceAndRequiresApprovalForWrites() {
        var careers = org.mockito.Mockito.mock(com.offerpilot.career.application.CareerRecommendForChat.class);
        var payload = new com.offerpilot.career.application.CareerRecommendForChat.Payload("IRC", List.of("技术研发", "数据分析"),
                List.of(new com.offerpilot.career.application.CareerRecommendForChat.Recommendation("occ1", "工程师", "技术", "IRC", 1, List.of("共同兴趣 IRC"))));
        org.mockito.Mockito.when(careers.recommend(org.mockito.ArgumentMatchers.eq("owner"), org.mockito.ArgumentMatchers.nullable(String.class))).thenReturn(new com.offerpilot.career.application.CareerRecommendForChat.Result(null, payload));
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("careers", careers);
        var registry = new ExpertRegistry(beans.getBeanProvider(com.offerpilot.job.application.JobMatchForChat.class),
                beans.getBeanProvider(com.offerpilot.resume.application.ResumeDiagnosisForChat.class), null, null, null, null,
                beans.getBeanProvider(com.offerpilot.career.application.CareerRecommendForChat.class),
                beans.getBeanProvider(com.offerpilot.agent.application.CareerWriteTools.class));
        var step = new PlanStep("s1", "职业推荐", ExpertType.CAREER_DISCOVERY, Map.of("action", "recommend"), List.of());
        var result = registry.execute("owner", Map.of(), "run", step, Map.of()).block(Duration.ofSeconds(3));
        assertThat(result.card().cardType()).isEqualTo("career_recommendation");
        assertThat(result.card().payload()).isEqualTo(payload);
        assertThat(result.summary()).doesNotContain("占位专家");
        assertThat(new ExpertRegistry().execute("owner", Map.of(), "run", step, Map.of()).block().summary())
                .contains("暂不可用").doesNotContain("占位专家");
        var write = new PlanStep("s1", "目标设置", ExpertType.CAREER_DISCOVERY, Map.of("action", "setCareerTarget", "occupationId", "occ1"), List.of());
        assertThatThrownBy(() -> registry.execute("owner", Map.of(), "run", write, Map.of()).block()).hasMessageContaining("必须先审批");
        org.mockito.Mockito.verify(careers).recommend("owner", null);
        org.mockito.Mockito.verifyNoMoreInteractions(careers);
    }
    @Test void resumeDiagnosisUsesOnlyExplicitContextAndReadsExistingReport() throws Exception {
        var facade=org.mockito.Mockito.mock(com.offerpilot.resume.application.ResumeDiagnosisForChat.class);
        var report=new ObjectMapper().readTree("{\"reportId\":\"diag1\",\"schemaVersion\":2,\"summary\":\"有依据的诊断\"}");
        org.mockito.Mockito.when(facade.execute(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString())).thenReturn(reactor.core.publisher.Mono.just(report));
        var beans=new org.springframework.beans.factory.support.StaticListableBeanFactory(); beans.addBean("diagnoses",facade);
        var registry=new ExpertRegistry(beans.getBeanProvider(com.offerpilot.job.application.JobMatchForChat.class),beans.getBeanProvider(com.offerpilot.resume.application.ResumeDiagnosisForChat.class));
        var step=new PlanStep("s1","诊断",ExpertType.RESUME,Map.of("resumeId","guessed","resumeVersionId","guessed"),List.of());
        assertThat(registry.execute("owner",Map.of(),"run",step,Map.of()).block().card()).isNull();
        org.mockito.Mockito.verifyNoInteractions(facade);
        var result=registry.execute("owner",Map.of("resumeId","rs1","resumeVersionId","v1","diagnosisReportId","diag1"),"run",step,Map.of()).block();
        assertThat(result.card().cardType()).isEqualTo("resume_diagnosis");
        assertThat(result.card().payload()).isEqualTo(report);
        org.mockito.Mockito.verify(facade).execute("owner","rs1","v1","diag1",ExpertRegistry.requestId("run","s1"));
    }
    private final ObjectMapper json = new ObjectMapper();
    private final List<List<String>> calls = new CopyOnWriteArrayList<>();

    @Test
    void requestIdIsStableUuidAndDoesNotReuseTheRunId() {
        String first = ExpertRegistry.requestId("run_1", "s1");
        assertThat(first).isEqualTo(ExpertRegistry.requestId("run_1", "s1"));
        assertThat(first).matches("[a-zA-Z0-9-]{16,80}");
        assertThat(first).doesNotContain("_");
        assertThat(first).isNotEqualTo(ExpertRegistry.requestId("run_2", "s1"));
    }

    @Test
    void bindsOnlyCallerContextAndNeverUsesModelInputIds() {
        ExpertRegistry registry = registry();
        Map<String, Object> context = ids("job_ctx", "res_ctx", "ver_ctx");

        ExpertResult fromContext = run(registry, context, job(Map.of("request", "匹配")));
        ExpertResult fromInputs = run(registry, context, job(Map.of(
                "jobId", " job_step ", "resumeId", "res_step", "resumeVersionId", "ver_step")));
        run(registry, context, job(Map.of("jobId", "job_only")));
        run(registry, Map.of(), job(ids("job_guessed", "res_guessed", "ver_guessed")));
        run(registry, Map.of("jobId", "job_ctx", "resumeId", "res_ctx"), job(Map.of("resumeVersionId", "ver_only")));
        ExpertResult resume = run(registry, context, new PlanStep("s1", "简历", ExpertType.RESUME, Map.of(), List.of()));
        run(registry, Map.of("jobId", 1, "resumeId", "res_ctx", "resumeVersionId", "ver_ctx"), job(Map.of()));

        assertThat(calls).containsExactly(
                List.of("owner", "job_ctx", "res_ctx", "ver_ctx"),
                List.of("owner", "job_ctx", "res_ctx", "ver_ctx"),
                List.of("owner", "job_ctx", "res_ctx", "ver_ctx"));
        assertThat(fromContext.card().cardId()).isEqualTo("match_01abc");
        assertThat(fromContext.card().payload().toString()).doesNotContain("快照");
        assertThat(fromInputs.card().cardType()).isEqualTo("job_match");
        assertThat(resume.card()).isNull();
        assertThat(resume.summary()).contains("未选择已保存的简历版本");
    }

    @Test
    void missingMatcherStaysPlaceholderEvenWhenIdsExist() {
        ExpertRegistry registry = new ExpertRegistry();
        ExpertResult result = registry.execute("owner", ids("job_1", "res_1", "ver_1"), "run", job(Map.of()), Map.of())
                .block(Duration.ofSeconds(3));
        assertThat(result.card()).isNull();
        assertThat(result.summary()).contains("未执行搜索");
        assertThat(registry.matching(job(Map.of()), ids("job_1", "res_1", "ver_1"))).isFalse();
    }

    @Test
    void matchFailurePropagatesWithoutInventingAnotherTarget() {
        ExpertRegistry registry = new ExpertRegistry((owner, jobId, resumeId, versionId, requestId) -> {
            throw new BusinessException(ErrorCode.MATCH_NOT_FOUND, "岗位不存在");
        });
        assertThatThrownBy(() -> registry.execute("owner", ids("job_1", "res_1", "ver_1"), "run", job(Map.of()), Map.of())
                        .block(Duration.ofSeconds(3)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("岗位不存在");
    }

    private ExpertRegistry registry() {
        return new ExpertRegistry((owner, jobId, resumeId, versionId, requestId) -> {
            calls.add(List.of(owner, jobId, resumeId, versionId));
            assertThat(requestId).isEqualTo(ExpertRegistry.requestId("run", "s1"));
            ObjectNode report = json.createObjectNode().put("id", "match_01abc").put("score", 10).put("summary", "可用");
            report.set("requirements", json.createArrayNode());
            report.set("jobSnapshot", json.createObjectNode().put("title", "快照"));
            return JobMatchCards.fromReport(report);
        });
    }

    private ExpertResult run(ExpertRegistry registry, Map<String, Object> context, PlanStep step) {
        return registry.execute("owner", context, "run", step, Map.of()).block(Duration.ofSeconds(3));
    }

    private static PlanStep job(Map<String, Object> inputs) {
        return new PlanStep("s1", "岗位", ExpertType.JOB, inputs, List.of());
    }

    private static Map<String, Object> ids(String jobId, String resumeId, String versionId) {
        return Map.of("jobId", jobId, "resumeId", resumeId, "resumeVersionId", versionId);
    }
}
