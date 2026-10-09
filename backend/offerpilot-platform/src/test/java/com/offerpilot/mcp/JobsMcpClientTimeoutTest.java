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
