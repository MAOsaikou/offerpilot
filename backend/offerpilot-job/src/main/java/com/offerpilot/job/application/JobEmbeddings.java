package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Optional local Ollama bge-m3 adapter. No cloud credential or private resume is sent here. */
@Component
public class JobEmbeddings {
    private final boolean enabled;
    private final URI endpoint;
    private final String model;
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();
    public JobEmbeddings(@Value("${offerpilot.jobs.embedding-enabled:false}") boolean enabled,
            @Value("${offerpilot.jobs.ollama-url:http://127.0.0.1:11434}") String base,
            @Value("${offerpilot.jobs.embedding-model:bge-m3}") String model,ObjectMapper json) {
        this.enabled=enabled; this.endpoint=URI.create(base.replaceAll("/$", "")+"/api/embed"); this.model=model; this.json=json;
    }
    public boolean enabled() { return enabled; }
    public String key() { return model+"@"+endpoint; }
    public String embed(String text) {
        if(!enabled) return null;
        try {
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(650)).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("model",model,"input",text)))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200 || response.body().length()>100000) return null;
            var vector=json.readTree(response.body()).path("embeddings").path(0);
            if(!vector.isArray() || vector.size()!=1024) return null;
            boolean nonzero=false;
            for(var value:vector) { if(!value.isNumber() || !Double.isFinite(value.asDouble())) return null; nonzero|=value.asDouble()!=0; }
            return nonzero?vector.toString():null;
        } catch(InterruptedException ex) { Thread.currentThread().interrupt(); return null; }
        catch(Exception ex) { return null; }
    }
}
