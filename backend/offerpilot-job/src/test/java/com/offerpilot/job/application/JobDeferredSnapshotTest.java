package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class JobDeferredSnapshotTest {
    final ObjectMapper json=new ObjectMapper();
    final JobSource source=mock(JobSource.class);
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);

    @Test void structuredPipelineDoesNoParserCallOrWriteUntilExplicitCommitAndCommitsEachSnapshotOnce() throws Exception {
        var parser=mock(JobSearchParser.class);
        var local=mock(JobLocalSearch.class);
        when(local.search(any(),any())).thenReturn(new JobLocalSearch.Result(List.of(),false));
        var raw=json.readTree("""
                {"jobs":[{"id":"external","site":"company","title":"Java工程师","company":"企业","nature":"intern"},
                         {"id":"external","site":"company","title":"Java工程师","company":"企业","nature":"intern"}],"_meta":{}}
                """);
        when(source.search(anyMap())).thenReturn(raw);
        when(jdbc.queryForObject(startsWith("INSERT INTO jobs"),any(RowMapper.class),anyString(),anyString(),anyString(),anyString(),anyString()))
                .thenAnswer(call -> ((com.fasterxml.jackson.databind.node.ObjectNode)json.readTree((String)call.getArgument(6))).put("id","job_stored"));
        try(var ignored=new WorkFixture()) {
            var pipeline=new JobSearchPipeline(ignored.work,parser,local,source,json);
            var jobs=new JobService(source,jdbc,json,pipeline);
            var prepared=new JobSearchForChat(jobs,json).prepareRuleSearch("Java","","intern","上海");
            verifyNoInteractions(parser);
            verify(jdbc,never()).queryForObject(startsWith("INSERT"),any(RowMapper.class),anyString(),anyString(),anyString(),anyString(),anyString());
            assertThat(prepared.preview().path("items")).hasSize(1);
            assertThat(prepared.preview().path("items").get(0).path("title").asText()).isEqualTo("Java工程师");
            var first=prepared.persist().get();
            assertThat(first.path("items").get(0).path("id").asText()).isEqualTo("job_stored");
            assertThat(prepared.persist().get()).isEqualTo(first);
            verify(jdbc,times(1)).queryForObject(startsWith("INSERT"),any(RowMapper.class),anyString(),anyString(),anyString(),anyString(),anyString());
            verify(source).search(argThat(args -> args.get("query").equals("Java") && args.get("location").equals("上海")));
        }
    }

    @Test void discardingPreparedSearchNeverWritesAndRetainsPreviouslyFetchedPublicFacts() throws Exception {
        var existing=json.createObjectNode().put("id","job_existing").put("title","已核实的详情标题")
                .put("detailFetchedAt","2026-10-20T00:00:00Z");
        when(jdbc.query(startsWith("SELECT id,payload,detail_fetched_at FROM jobs WHERE site="),any(RowMapper.class),anyString(),anyString(),anyString()))
                .thenReturn(List.of(existing));
        when(source.search(anyMap())).thenReturn(json.readTree("{\"jobs\":[{\"id\":\"external\",\"site\":\"company\",\"title\":\"搜索列表标题\"}],\"_meta\":{}}"));
        var prepared=new JobService(source,jdbc,json).prepareRuleSearch("Java","","intern","上海");
        assertThat(prepared.preview().path("items").get(0).path("title").asText()).isEqualTo("已核实的详情标题");
        verify(jdbc,never()).queryForObject(startsWith("INSERT"),any(RowMapper.class),anyString(),anyString(),anyString(),anyString(),anyString());
    }

    static class WorkFixture implements AutoCloseable {
        final JobSearchWork work=new JobSearchWork();
        public void close() { work.close(); }
    }
}
