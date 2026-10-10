package com.offerpilot.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;

/** Spring AI 1.0 has no thinking option. Extend only DeepSeek request serialization. */
final class DeepSeekRequestSupport {
    private final ObjectMapper requests;
    private final java.time.Duration timeout;

    DeepSeekRequestSupport(String thinking, java.time.Duration timeout) {
        this.timeout = timeout;
        ObjectMapper plain = new ObjectMapper().findAndRegisterModules()
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        SimpleModule module = new SimpleModule();
        module.addSerializer(DeepSeekApi.ChatCompletionRequest.class, serializer(plain, thinking));
        module.addSerializer(OpenAiApi.ChatCompletionRequest.class, serializer(plain, thinking));
        requests = plain.copy().registerModule(module);
    }

    private static <T> JsonSerializer<T> serializer(ObjectMapper plain, String thinking) {
        return new JsonSerializer<>() {
            @Override public void serialize(T value, JsonGenerator generator, SerializerProvider provider) throws IOException {
                ObjectNode body = plain.valueToTree(value);
                boolean tools = body.path("tools").isArray() && !body.path("tools").isEmpty();
                for (var message : body.path("messages")) {
                    tools |= "tool".equals(message.path("role").asText()) || !message.path("tool_calls").isEmpty();
                }
                // Native Spring AI does not echo reasoning_content. Disable throughout tool loops,
                // including tool history without current definitions; never synthesize reasoning.
                body.putObject("thinking").put("type", tools ? "disabled" : thinking);
                generator.writeTree(body);
            }
        };
    }

    RestClient.Builder restClient() {
        var http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        return RestClient.builder().requestFactory(factory).messageConverters(converters -> {
            // Prepend a writer only; keep the default response reader and its unknown-field support.
            converters.add(0, new MappingJackson2HttpMessageConverter(requests) {
                @Override public boolean canRead(Class<?> clazz, org.springframework.http.MediaType mediaType) { return false; }
                @Override public boolean canRead(java.lang.reflect.Type type, Class<?> context, org.springframework.http.MediaType mediaType) { return false; }
            });
        });
    }

    WebClient.Builder webClient() {
        var http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
        return WebClient.builder().clientConnector(new org.springframework.http.client.reactive.JdkClientHttpConnector(http)).codecs(codecs -> codecs.defaultCodecs().jackson2JsonEncoder(new Jackson2JsonEncoder(requests)));
    }
}
