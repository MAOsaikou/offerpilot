package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JobEvalReportTest {
    final ObjectMapper json = new ObjectMapper();

    ObjectNode report(String... statuses) throws Exception {
        var root = json.createObjectNode().put("id", "match_1").put("summary", "fixture");
        var req = root.putArray("requirements");
        for (String status : statuses) {
            req.addObject().put("requirement", "Java").put("status", status)
                    .put("jdQuote", "熟悉Java").put("resumePath", "/summary")
                    .put("resumeQuote", "MET".equals(status) || "PARTIAL".equals(status) ? "做过 Java" : "")
                    .put("reason", "fixture").put("suggestion", "补细节");
        }
        return root;
    }

    ObjectNode job(boolean salary, boolean redFlag) {
        var job = json.createObjectNode().put("id", "job_1").put("title", "Java").put("company", "示例")
                .put("nature", "campus").put("description", "参与后端接口开发").put("requirement", "掌握 Java 与 Spring")
                .put("locations", "北京").put("source", "LOCAL_SNAPSHOT");
        job.putArray("skills").add("Java");
        if (salary) job.put("description", job.path("description").asText() + " 月薪 15-25k");
        if (redFlag) job.put("requirement", job.path("requirement").asText() + " 不缴纳社保 无底薪");
        return job;
    }

    @Test
    void strongMatchGradesBetterThanMissingHeavy() throws Exception {
        var strong = JobEvalReport.evaluate(report("MET", "MET", "PARTIAL"), job(true, false));
        var weak = JobEvalReport.evaluate(report("MISSING", "MISSING", "PARTIAL"), job(false, true));
        assertThat(strong.path("overall").asInt()).isGreaterThan(weak.path("overall").asInt());
        assertThat(strong.path("dimensions").size()).isEqualTo(6);
        assertThat(strong.path("dimensions").get(0).path("code").asText()).isEqualTo("A");
        assertThat(weak.path("dimensions").get(4).path("score").asInt()).isLessThan(60);
        assertThat(strong.path("grade").asText()).isIn("A", "B", "C", "D");
    }

    @Test
    void emptyRequirementsStillReturnsStructuredCard() throws Exception {
        var empty = json.createObjectNode().put("id", "match_empty");
        empty.putArray("requirements");
        var eval = JobEvalReport.evaluate(empty, job(false, false));
        assertThat(eval.path("overall").asInt()).isBetween(0, 100);
        assertThat(eval.path("advice").isArray()).isTrue();
    }
}
