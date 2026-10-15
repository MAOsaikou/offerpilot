package com.offerpilot.job.application;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DNS rebinding: the address that passed the public-address check must be the address connected to. */
class JobPageFetcherPinningTest {
    @Test void hostIsResolvedOncePerHopAndEveryAnswerMustBePublic() throws Exception {
        var lookups = new AtomicInteger();
        // A rebinding DNS server: public on the first lookup, loopback afterwards.
        var fetcher = new JobPageFetcher(true, host -> new InetAddress[]{InetAddress.getByName(
                lookups.getAndIncrement() == 0 ? "223.5.5.5" : "127.0.0.1")}, JobPageFetcher::isPublic);
        var target = fetcher.target("https://jobs.example.test/p/1");
        assertThat(target.address().getHostAddress()).isEqualTo("223.5.5.5");
        assertThat(lookups).hasValue(1);

        var mixed = new JobPageFetcher(true, host -> new InetAddress[]{InetAddress.getByName("223.5.5.5"),
                InetAddress.getByName("10.0.0.8")}, JobPageFetcher::isPublic);
        assertThatThrownBy(() -> mixed.target("https://jobs.example.test/p/1"))
                .isInstanceOf(com.offerpilot.common.error.BusinessException.class);
    }

    @Test void requestGoesToThePinnedAddressWithTheOriginalHostNameAndDecodesChunkedBodies() throws Exception {
        var host = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/p", exchange -> {
            host.set(exchange.getRequestHeaders().getFirst("Host") + " " + exchange.getRequestURI());
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, 0); // length 0 = chunked
            try (var out = exchange.getResponseBody()) {
                for (int i = 0; i < 3; i++) { out.write(("<p>第" + i + "段岗位职责</p>").getBytes(StandardCharsets.UTF_8)); out.flush(); }
            }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            // The name does not exist in DNS, so the request can only succeed through the pinned address.
            var uri = URI.create("http://jobs.invalid:" + port + "/p?id=7");
            try (var response = PinnedHttpGet.get(uri, InetAddress.getLoopbackAddress(), Duration.ofSeconds(2), Duration.ofSeconds(5), Map.of("Accept", "text/html"))) {
                assertThat(response.status()).isEqualTo(200);
                assertThat(response.header("content-type")).startsWith("text/html");
                try (InputStream body = response.body()) {
                    assertThat(new String(body.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("<p>第0段岗位职责</p><p>第1段岗位职责</p><p>第2段岗位职责</p>");
                }
            }
            assertThat(host.get()).isEqualTo("jobs.invalid:" + port + " /p?id=7");
        } finally { server.stop(0); }
    }
}
