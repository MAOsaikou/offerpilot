package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.*;
import com.offerpilot.llm.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import reactor.core.publisher.Flux;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobDiscoveryTest {
    final ObjectMapper json=new ObjectMapper();
    final ModelGateway gateway=mock(ModelGateway.class);
    final JobSearchParser parser=new JobSearchParser(gateway,json);
    final JobSearchWork work=new JobSearchWork();
    @AfterEach void close() { work.close(); }
    void model(String text) { when(gateway.stream(eq(ModelTier.FLASH),anyList(),eq(0.0))).thenReturn(Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))))); }
    @Test void structuredKeywordsWithOrWithoutFiltersNeverCallModel() {
        assertThat(parser.parse("owner"," Java ","","campus","")).isEqualTo(
                new JobSearchParser.Criteria("Java","","","campus","","rule"));
        assertThat(parser.parse("owner","Java  Spring Boot","tencent","intern","上海")).isEqualTo(
                new JobSearchParser.Criteria("Java Spring Boot","","tencent","intern","上海","rule"));
        verifyNoInteractions(gateway);
    }
    @Test void flashExtractsGroundedFieldsAndExplicitFiltersWin() {
        model("{\"company\":\"腾讯\",\"location\":\"上海\",\"nature\":\"intern\",\"query\":\"Java\"}");
        var parsed=parser.parse("owner","找上海的腾讯 Java 实习岗位","","all","");
        assertThat(parsed).isEqualTo(new JobSearchParser.Criteria("Java","腾讯","tencent","intern","上海","FLASH"));
        parsed=parser.parse("owner","找上海的腾讯 Java 实习岗位","didi","social","北京");
        assertThat(parsed.site()).isEqualTo("didi"); assertThat(parsed.location()).isEqualTo("北京"); assertThat(parsed.nature()).isEqualTo("social");
        verify(gateway,atLeastOnce()).stream(eq(ModelTier.FLASH),anyList(),eq(0.0));
    }
    @Test void invalidOrInventedModelFieldsFallBackWithoutInventedFilters() {
        model("{\"company\":\"不存在的企业\",\"location\":\"火星\",\"nature\":\"social\",\"query\":\"Python\"}");
        var parsed=parser.parse("owner","找上海的腾讯 Java 实习岗位","","all","");
        assertThat(parsed.company()).isEqualTo("腾讯"); assertThat(parsed.location()).isEqualTo("上海"); assertThat(parsed.query()).isEqualTo("Java"); assertThat(parsed.nature()).isEqualTo("intern");
        model("broken"); assertThat(parser.parse("owner","找Java岗位","","all","").parser()).isEqualTo("rule");
    }
    JsonNode job(String id,String url,String retrieval) {
        return json.createObjectNode().put("id",id).put("externalId","external").put("title","Java").put("site","fixture").put("nature","intern").put("url",url).put("retrievalSource",retrieval);
    }
    @Test void mergesDatabaseAndMcpByCanonicalUrlAndRetainsBothSources() throws Exception {
        var local=mock(JobLocalSearch.class); var source=mock(JobSource.class);
        var a=job("job_a","https://example.test/j/1?utm_source=x","LOCAL_DB");
        when(local.search(any(),any())).thenReturn(new JobLocalSearch.Result(List.of(a),false));
        var remote=json.readTree("{\"jobs\":[{\"id\":\"external\",\"site\":\"fixture\",\"title\":\"Java\",\"nature\":\"intern\"}],\"_meta\":{\"sites\":[\"fixture\"],\"skipped\":[],\"degradedSites\":[]}}");
        when(source.search(anyMap())).thenReturn(remote);
        var pipeline=new JobSearchPipeline(work,parser,local,source,json);
        var result=pipeline.search("owner","Java","","all","",(raw,nature)->job("job_a","https://example.test/j/1","JOBS_MCP"));
        assertThat(result.path("items")).hasSize(1);
        assertThat(result.path("items").get(0).path("retrievalSources").toString()).contains("LOCAL_DB","JOBS_MCP");
        assertThat(result.path("meta").path("partial").asBoolean()).isFalse();
    }
    @Test void slowMcpReturnsStoredPartialWithinWholeRequestBudgetAndCancelsWork() {
        var local=mock(JobLocalSearch.class); var source=mock(JobSource.class);
        when(local.search(any(),any())).thenReturn(new JobLocalSearch.Result(List.of(job("job_a","","LOCAL_DB")),false));
        var interrupted=new CountDownLatch(1);
        when(source.search(anyMap())).thenAnswer(call->{ try { new CountDownLatch(1).await(30,TimeUnit.SECONDS); } catch(InterruptedException ex) { interrupted.countDown(); Thread.currentThread().interrupt(); } return null; });
        long start=System.nanoTime();
        var result=new JobSearchPipeline(work,parser,local,source,json).search("owner","Java","","all","",(raw,nature)->raw);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)).isLessThan(8000);
        assertThat(result.path("items")).hasSize(1); assertThat(result.path("meta").path("partial").asBoolean()).isTrue();
        assertThat(result.path("meta").path("timedOut").asBoolean()).isTrue();
        assertThatCode(()->assertThat(interrupted.await(1,TimeUnit.SECONDS)).isTrue()).doesNotThrowAnyException();
    }
    @Test void canonicalUrlKeepsJobIdQueryParameters() {
        assertThat(JobSearchPipeline.identity(job("a","https://example.test/jobs?jobId=1&utm_source=x","LOCAL_DB")))
                .isEqualTo(JobSearchPipeline.identity(job("b","https://example.test/jobs?jobId=1","JOBS_MCP")));
        assertThat(JobSearchPipeline.identity(job("a","https://example.test/jobs?jobId=1","LOCAL_DB")))
                .isNotEqualTo(JobSearchPipeline.identity(job("b","https://example.test/jobs?jobId=2","JOBS_MCP")));
    }
    @Test void liveFailureDoesNotDiscardLocalJobs() {
        var local=mock(JobLocalSearch.class); var source=mock(JobSource.class);
        when(local.search(any(),any())).thenReturn(new JobLocalSearch.Result(List.of(job("job_a","","LOCAL_DB")),false));
        when(source.search(anyMap())).thenThrow(new IllegalStateException("private provider error"));
        var result=new JobSearchPipeline(work,parser,local,source,json).search("owner","Java","","all","",(raw,nature)->raw);
        assertThat(result.path("items")).hasSize(1); assertThat(result.path("meta").path("timedOut").asBoolean()).isFalse();
        assertThat(result.toString()).doesNotContain("private provider error");
    }
}
