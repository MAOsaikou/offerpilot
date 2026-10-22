package com.offerpilot;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ResumeFieldRecallScoreTest {
    @Test void scoresFailureAsZeroAndNeverRewardsShortInventedFragmentsOrCertificateFalsePositives() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var expected = json.readTree("""
            {"basics":{"name":"虚构同学","phone":"13800138000","email":"fixture@example.test","city":"杭州","links":[]},
             "education":[],"experience":[],"projects":[],"skills":[],"certificates":["软件设计师"],"summary":"负责缓存开发和验证"}
            """);
        var perfect = new ResumeFieldRecallScore(); perfect.resume("fictional", expected, expected);
        assertThat(perfect.overall()).isEqualTo(1.0);
        var failed = new ResumeFieldRecallScore(); failed.resume("failure", expected, json.createObjectNode());
        assertThat(failed.overall()).isZero();
        assertThat(failed.misses).hasSize(6);
        assertThat(ResumeFieldRecallScore.overlaps("缓存", "负责缓存开发和验证")).isFalse();
        assertThat(ResumeFieldRecallScore.date("Jun 2024")).isEqualTo(ResumeFieldRecallScore.date("2024年6月"));
        var invented = expected.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)invented).withArray("certificates").add("虚构奖项");
        var score = new ResumeFieldRecallScore(); score.resume("invented", expected, invented);
        assertThat(score.overall()).isEqualTo(1.0); // Recall and precision have separate denominators.
        assertThat(score.recall().get("certificates.precision")).contains("1/2");
    }
}
