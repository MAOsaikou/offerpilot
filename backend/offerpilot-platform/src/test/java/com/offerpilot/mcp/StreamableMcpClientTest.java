package com.offerpilot.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** No listening sockets: real serializer, session handling, frames, limits and deadlines. */
class StreamableMcpClientTest {
    final ObjectMapper json=new ObjectMapper();
    final HttpClient http=mock(HttpClient.class);
    @SuppressWarnings("unchecked")
    private HttpResponse<byte[]> response(int status,String body,Map<String,List<String>> headers) {
        var response=(HttpResponse<byte[]>)mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);when(response.body()).thenReturn(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(response.headers()).thenReturn(HttpHeaders.of(headers,(a,b)->true));return response;
    }
    private HttpResponse<byte[]> envelope(int id,String result) {
        return response(200,"{\"jsonrpc\":\"2.0\",\"id\":"+id+",\"result\":"+result+"}",Map.of());
    }
    @Test void discoversPaginatedSchemasWithSessionAndBearer() throws Exception {
        var init=response(200,"{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2025-03-26\"}}",Map.of("Mcp-Session-Id",List.of("fixture-session")));
        var notify=response(202,"",Map.of());
        var first=envelope(2,"{\"tools\":[{\"name\":\"get_me\"}],\"nextCursor\":\"second\"}");
        var second=envelope(3,"{\"tools\":[{\"name\":\"search_code\"}]}");
        when(http.sendAsync(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any()))
                .thenReturn(CompletableFuture.completedFuture(init),CompletableFuture.completedFuture(notify),CompletableFuture.completedFuture(first),CompletableFuture.completedFuture(second));
        var result=new StreamableMcpClient(json,"https://fixed.example/mcp","fixture-token",http).execute("tools/list",Map.of(),1000);
        assertThat(result.path("tools")).hasSize(2);
        var requests=ArgumentCaptor.forClass(HttpRequest.class);
        verify(http,times(5)).sendAsync(requests.capture(),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        assertThat(requests.getAllValues()).allMatch(r->r.headers().firstValue("Authorization").orElse("").equals("Bearer fixture-token"));
        assertThat(requests.getAllValues().get(0).headers().firstValue("Mcp-Session-Id")).isEmpty();
        assertThat(requests.getAllValues().subList(1,5)).allMatch(r->r.headers().firstValue("Mcp-Session-Id").orElse("").equals("fixture-session"));
        assertThat(requests.getAllValues().get(4).method()).isEqualTo("DELETE");
    }
    @Test void completesAnOpenSseStreamOnMatchingFrame() throws Exception {
        var init=envelope(1,"{\"protocolVersion\":\"2025-03-26\"}");
        var notify=response(202,"",Map.of());
        var count=new java.util.concurrent.atomic.AtomicInteger();
        when(http.sendAsync(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenAnswer(invocation->{
            int step=count.getAndIncrement();
            if(step==0) return CompletableFuture.completedFuture(init);
            if(step==1) return CompletableFuture.completedFuture(notify);
            HttpResponse.BodyHandler<byte[]> handler=invocation.getArgument(1);
            var info=mock(HttpResponse.ResponseInfo.class);
            when(info.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type",List.of("text/event-stream")),(a,b)->true));
            var subscriber=handler.apply(info);
            var subscription=mock(Flow.Subscription.class);subscriber.onSubscribe(subscription);
            String frame="data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\r\n\r\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[]}}\r\n\r\n";
            byte[] bytes=frame.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            subscriber.onNext(List.of(ByteBuffer.wrap(Arrays.copyOfRange(bytes,0,35))));
            assertThat(subscriber.getBody().toCompletableFuture()).isNotCompleted();
            subscriber.onNext(List.of(ByteBuffer.wrap(Arrays.copyOfRange(bytes,35,bytes.length))));
            assertThat(subscriber.getBody().toCompletableFuture()).isCompleted();verify(subscription).cancel();
            // No onComplete: real servers may leave their SSE stream open after the response.
            return subscriber.getBody().thenApply(body->response(200,new String(body,java.nio.charset.StandardCharsets.UTF_8),Map.of("Content-Type",List.of("text/event-stream")))).toCompletableFuture();
        });
        var output=new StreamableMcpClient(json,"https://fixed.example/mcp","",http).execute("tools/call",Map.of("name","get_me","arguments",Map.of()),1000);
        assertThat(output.path("content")).isEmpty();
    }
    @Test void rejectsRedirectMalformedIdsAndExcessiveBodies() {
        var responses=List.of(response(302,"",Map.of("Location",List.of("http://127.0.0.1"))),envelope(99,"{}"),response(200,"x".repeat(2_000_001),Map.of()));
        for(var response:responses) {
            reset(http);
            when(http.sendAsync(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(CompletableFuture.completedFuture(response));
            assertThatThrownBy(()->new StreamableMcpClient(json,"https://fixed.example/mcp","fixture",http).execute("tools/list",Map.of(),1000)).isInstanceOf(Exception.class);
            verify(http).sendAsync(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any());
        }
    }
    @Test void wholeOperationTimeoutCancelsPendingHttp() {
        var pending=new CompletableFuture<HttpResponse<byte[]>>();
        when(http.sendAsync(any(HttpRequest.class),org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<byte[]>>any())).thenReturn(pending);
        assertThatThrownBy(()->new StreamableMcpClient(json,"https://fixed.example/mcp","",http).execute("tools/list",Map.of(),10)).isInstanceOf(java.util.concurrent.TimeoutException.class);
        assertThat(pending).isCancelled();
    }
}
