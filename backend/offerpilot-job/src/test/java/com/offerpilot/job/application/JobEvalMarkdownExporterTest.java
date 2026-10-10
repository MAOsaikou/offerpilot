package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.common.error.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobEvalMarkdownExporterTest {
    final ObjectMapper json = new ObjectMapper();

    @Test
    void rendersGradeDimensionsAndDisclaimer() {
        ObjectNode report = json.createObjectNode().put("id", "match_1");
        report.putArray("requirements").addObject()
                .put("requirement", "Java").put("status", "MET").put("resumeQuote", "做过 Java");
        ObjectNode job = json.createObjectNode().put("id", "job_1").put("title", "Java 后端")
                .put("company", "示例科技").put("nature", "campus")
                .put("description", "参与后端接口 月薪 15-25k").put("requirement", "掌握 Java")
                .put("locations", "上海");
        job.putArray("skills").add("Java");
        report.set("jobSnapshot", job);
        ObjectNode withEval = (ObjectNode) JobMatchService.withEvaluation(report);

        JobMatchService matches = mock(JobMatchService.class);
        when(matches.get("alice", "match_1")).thenReturn(withEval);
        String md = new JobEvalMarkdownExporter(matches).markdown("alice", "match_1");
        assertThat(md).contains("Java 后端", "示例科技", "A–F", "综合分：", "等级：", "A 要求覆盖", "job-eval-v1");
        assertThat(md).contains("不是录用预测");
        assertThat(md).doesNotContain("jobSnapshot");
    }

    @Test
    void missingEvaluationIsNotFound() {
        JobMatchService matches = mock(JobMatchService.class);
        when(matches.get("alice", "match_x")).thenReturn(json.createObjectNode().put("id", "match_x"));
        assertThatThrownBy(() -> new JobEvalMarkdownExporter(matches).markdown("alice", "match_x"))
                .isInstanceOf(BusinessException.class);
    }
}
