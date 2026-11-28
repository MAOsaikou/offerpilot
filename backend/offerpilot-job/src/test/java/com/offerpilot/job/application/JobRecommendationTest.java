package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class JobRecommendationTest {
    final ObjectMapper json=new ObjectMapper();
    JsonNode job(String id,String title,String requirement,String city,String source) {
        return json.createObjectNode().put("id",id).put("title",title).put("requirement",requirement).put("locations",city).put("source",source);
    }
    @Test void ranksFromTargetProfileResumeAndCityWithActualReasonsAndNoFakeJobs() {
        var context=new JobRecommendationContext.Context("后端开发","上海","Java SQL","Spring Java");
        var a=job("job_a","后端开发工程师","Java SQL Spring","上海","company-career-site");
        var b=job("job_b","后端开发","SQL","北京","company-career-site");
        var fake=job("job_fake","后端开发","Java SQL Spring","上海","LOCAL_SNAPSHOT");
        var expired=((com.fasterxml.jackson.databind.node.ObjectNode)a.deepCopy()).put("id","job_old").put("deadline","2020-01-01");
        var ranked=JobRecommendationService.rank(context,List.of(b,fake,a,expired));
        assertThat(ranked).hasSize(2); assertThat(ranked.get(0).path("jobId").asText()).isEqualTo("job_a");
        var reasons = ranked.get(0).path("reasons").toString();
        assertThat(reasons).contains("目标职业匹配：后端开发","画像技能/专业","保存简历","上海");
        assertThat(reasons).doesNotContain("后端、端开", "端开、开发", "、端开", "端开、");
        assertThat(ranked.get(0).path("score").asInt()).isBetween(1,100);
    }
    @Test void mergesFullPhrasesWithoutFragmentedSlices() {
        var context=new JobRecommendationContext.Context("后端开发工程师","上海","并发与自动化测试","Java 后端开发");
        var jobA=job("job_1","后端开发工程师","参与高并发架构设计，负责系统开发与压力测试，要求Java与后端开发能力","上海","company-career-site");
        var ranked=JobRecommendationService.rank(context,List.of(jobA));
        assertThat(ranked).hasSize(1);
        var reasons=ranked.get(0).path("reasons").toString();
        assertThat(reasons).contains("目标职业匹配：后端开发工程师");
        assertThat(reasons).contains("画像技能/专业匹配：并发、测试");
        assertThat(reasons).contains("保存简历匹配：java、后端开发");
        assertThat(reasons).doesNotContain("后端、端开、开发、发工、工程、程师");
        assertThat(reasons).doesNotContain("后端、端开", "端开、开发", "开发、发工", "发工、工程", "工程、程师", "并发、发与", "发与、测试");
        assertThat(reasons).doesNotContain("、端开", "端开、", "、发工", "发工、", "、程师", "、发与", "发与、");
    }
    @Test void emptyContextOrEmptyDatabaseNeverCreatesSampleJobs() {
        assertThat(JobRecommendationService.rank(new JobRecommendationContext.Context("","","",""),List.of(job("job_a","Java","Java","上海","company-career-site")))).isEmpty();
        assertThat(JobRecommendationService.rank(new JobRecommendationContext.Context("Java","上海","Java","Java"),List.of())).isEmpty();
    }
}
