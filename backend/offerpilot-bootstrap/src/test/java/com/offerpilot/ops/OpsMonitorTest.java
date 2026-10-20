package com.offerpilot.ops;
import com.offerpilot.monitoring.HttpErrorMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class OpsMonitorTest {
    @Test void thresholdsTransitionsAndPushOptInUseOnlyAggregatedJournal() {
        var jdbc=mock(JdbcTemplate.class);
        var meters=new SimpleMeterRegistry();
        var http=new HttpErrorMetrics(meters);
        var env=new MockEnvironment().withProperty("offerpilot.ops.min-requests","2").withProperty("offerpilot.ops.hourly-token-threshold","100");
        var channel=mock(OpsNotificationChannel.class);
        var beans=new StaticListableBeanFactory();beans.addBean("test",channel);
        var ops=new OpsMonitor(jdbc,http,env,beans.getBeanProvider(OpsNotificationChannel.class),meters);
        when(jdbc.queryForObject(anyString(),org.mockito.ArgumentMatchers.<RowMapper<OpsMonitor.Usage>>any())).thenReturn(new OpsMonitor.Usage(2,1,80,30,1));
        meters.counter("offerpilot.output.blocked","category","SECRET").increment(2);
        http.record(false);http.record(true);
        assertThat(ops.check().alerts()).containsExactly("HTTP_ERROR_RATE","MODEL_TOKEN_USAGE");
        assertThat(ops.status().usage().unreported()).isEqualTo(1);
        assertThat(ops.status().moderationHits()).containsEntry("SECRET",2.0);
        assertThat(meters.get("offerpilot.model.tokens.hour").gauge().value()).isEqualTo(110);
        verifyNoInteractions(channel);
        env.withProperty("offerpilot.ops.external-push-enabled","true");
        ops.check();verifyNoInteractions(channel); // no duplicate transition
        env.withProperty("offerpilot.ops.error-rate-threshold","1");
        when(jdbc.queryForObject(anyString(),org.mockito.ArgumentMatchers.<RowMapper<OpsMonitor.Usage>>any())).thenReturn(new OpsMonitor.Usage(1,0,1,0,0));
        assertThat(ops.check().state()).isEqualTo("UP");
        verify(channel).notify("HTTP_ERROR_RATE",false);verify(channel).notify("MODEL_TOKEN_USAGE",false);
        when(jdbc.queryForObject(anyString(),org.mockito.ArgumentMatchers.<RowMapper<OpsMonitor.Usage>>any())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("fixture"));
        assertThat(ops.check().alerts()).containsExactly("USAGE_UNAVAILABLE");
        verify(channel).notify("USAGE_UNAVAILABLE",true);
        verify(jdbc,atLeastOnce()).queryForObject(contains("FROM llm_usage_logs"),org.mockito.ArgumentMatchers.<RowMapper<OpsMonitor.Usage>>any());
    }
}
