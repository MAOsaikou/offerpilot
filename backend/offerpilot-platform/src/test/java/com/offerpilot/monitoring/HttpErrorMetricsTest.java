package com.offerpilot.monitoring;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
class HttpErrorMetricsTest {
    @Test void windowExpiresAndIsFiniteWhenIdle() {
        final Instant[] now={Instant.parse("2026-10-20T00:00:00Z")};
        var clock=new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now[0]; }
        };
        var meters=new SimpleMeterRegistry();var metrics=new HttpErrorMetrics(meters,clock);
        metrics.record(false);metrics.record(true);
        assertThat(metrics.window().errorRate()).isEqualTo(0.5);
        assertThat(meters.get("offerpilot.http.error.rate").gauge().value()).isEqualTo(0.5);
        now[0]=now[0].plusSeconds(300);
        assertThat(metrics.window().requests()).isZero();
        assertThat(metrics.window().errorRate()).isZero();
    }
}
