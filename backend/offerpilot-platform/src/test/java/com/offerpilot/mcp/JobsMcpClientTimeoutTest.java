package com.offerpilot.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class JobsMcpClientTimeoutTest {
    @Test void defaultLiveBudgetAndPropertyOverrideAreBound() {
        for (var properties : java.util.List.of(Map.<String,Object>of(),
                Map.<String,Object>of("offerpilot.mcp.jobs-timeout-ms", "125"),
                Map.<String,Object>of("JOBS_LIVE_TIMEOUT_MS", "125"))) {
            try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(
                        new org.springframework.core.env.MapPropertySource("test", properties));
                context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
                context.register(JobsMcpClient.class);
                context.refresh();
                assertThat(ReflectionTestUtils.getField(context.getBean(JobsMcpClient.class), "timeoutMs"))
                        .isEqualTo(properties.isEmpty() ? 6500L : 125L);
            }
        }
    }
    @Test void wholeProtocolCallHasBoundedTimeout() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.createContext("/mcp", exchange -> {
            try { Thread.sleep(2000); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            var client = new JobsMcpClient(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort());
            ReflectionTestUtils.setField(client, "timeoutMs", 100L);
            assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                assertThatThrownBy(() -> client.call("search_jobs", Map.of("query", "Java")))
                    .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.TOOL_FAILED)));
        } finally { server.stop(0); executor.shutdownNow(); }
    }
}
