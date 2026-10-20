package com.offerpilot.ops;

import com.offerpilot.monitoring.HttpErrorMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.*;
import org.springframework.test.context.support.TestPropertySourceUtils;
import java.util.concurrent.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class OpsSchedulingTest {
    @Test void monitorRunsEvenWhenResumeWorkerIsDisabled() throws Exception {
        var called=new CountDownLatch(1);
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),org.mockito.ArgumentMatchers.<RowMapper<OpsMonitor.Usage>>any())).thenAnswer(call -> {
            called.countDown();return new OpsMonitor.Usage(0,0,0,0,0);
        });
        try(var context=new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,"offerpilot.resume.worker-enabled=false",
                    "offerpilot.ops.initial-delay-ms=1","offerpilot.ops.check-delay-ms=60000");
            context.registerBean(JdbcTemplate.class,()->jdbc);
            context.registerBean(MeterRegistry.class,SimpleMeterRegistry::new);
            context.register(OpsScheduling.class,OpsMonitor.class,HttpErrorMetrics.class);
            context.refresh();
            assertThat(called.await(3,TimeUnit.SECONDS)).isTrue();
        }
    }
}
