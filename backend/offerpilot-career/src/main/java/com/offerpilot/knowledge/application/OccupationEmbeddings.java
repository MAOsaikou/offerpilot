package com.offerpilot.knowledge.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Optional provider: missing credentials/model and supplier failures never disable Holland rules. */
@Component
public class OccupationEmbeddings {
    private static final Logger log = LoggerFactory.getLogger(OccupationEmbeddings.class);
    // Bailian text-embedding-v3/v4 accepts at most ten inputs per HTTP request.
    private static final int PROVIDER_BATCH_SIZE = 10;
    private final ObjectProvider<EmbeddingModel> models;
    private final Environment env;

    public OccupationEmbeddings(ObjectProvider<EmbeddingModel> models, Environment env) {
        this.models = models;
        this.env = env;
    }

    public boolean configured() {
        boolean mock = Arrays.asList(env.getActiveProfiles()).contains("mock-llm");
        String key = env.getProperty("spring.ai.openai.embedding.api-key", env.getProperty("spring.ai.openai.api-key", ""));
        return mock || !("none".equals(env.getProperty("spring.ai.model.embedding"))
                || key.isBlank() || "sk-not-configured".equals(key));
    }

    /** Vectors from different models/endpoints share no space; stored vectors are tagged with this. */
    public String fingerprint() {
        String base = env.getProperty("spring.ai.openai.embedding.base-url", env.getProperty("spring.ai.openai.base-url", ""));
        String host = base.replaceFirst("^[a-z]+://", "").replaceFirst("/.*$", "");
        if (host.matches("(localhost|127\\.0\\.0\\.1|host\\.docker\\.internal|ollama)(:\\d+)?")) host = "local";
        return env.getProperty("spring.ai.openai.embedding.options.model", "") + "@" + host;
    }

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.offerpilot.llm.privacy.PiiMaskingPreference privacy;
    public List<float[]> embed(List<String> documents) {
        long started = System.nanoTime();
        try { return embedDocuments(documents); }
        finally {
            var scope = com.offerpilot.llm.UsageJournal.current();
            if (scope != null) log.debug("AG02 run={} phase=embedding elapsedMs={}", scope.runId(), (System.nanoTime() - started) / 1_000_000);
        }
    }

    private List<float[]> embedDocuments(List<String> documents) {
        if (!configured()) {
            log.warn("职业向量不可用：embedding 已关闭或未配置 Bailian Key；保留全文索引与规则推荐");
            return List.of();
        }
        try {
            EmbeddingModel model = models.getIfAvailable();
            if (model == null) {
                log.warn("职业向量不可用：无 EmbeddingModel；保留规则推荐");
                return List.of();
            }
            String owner = com.offerpilot.llm.quota.LlmUserContext.current();
            var outbound = owner == null || privacy == null || privacy.enabled(owner)
                    ? documents.stream().map(text -> new com.offerpilot.llm.privacy.PiiMasker.Session(List.of(text)).mask(text)).toList() : documents;
            List<float[]> vectors = new ArrayList<>(documents.size());
            for (int offset = 0; offset < outbound.size(); offset += PROVIDER_BATCH_SIZE) {
                var batch = outbound.subList(offset, Math.min(offset + PROVIDER_BATCH_SIZE, outbound.size()));
                var result = model.embed(batch);
                if (result.size() != batch.size()) throw new IllegalArgumentException("Embedding batch size mismatch");
                result.forEach(OccupationEmbeddings::literal);
                vectors.addAll(result);
            }
            return List.copyOf(vectors);
        } catch (Exception ex) {
            // Supplier exception messages may contain URLs/credentials; log only the exception type.
            log.warn("职业向量调用失败 ({})；保留全文索引与规则推荐", ex.getClass().getSimpleName());
            return List.of();
        }
    }

    public static String literal(float[] vector) {
        if (vector == null || vector.length != 1024) throw new IllegalArgumentException("Expected 1024 dimensions");
        StringBuilder result = new StringBuilder("[");
        boolean nonzero = false;
        for (int i = 0; i < vector.length; i++) {
            if (!Float.isFinite(vector[i])) throw new IllegalArgumentException("Non-finite embedding");
            nonzero |= vector[i] != 0;
            if (i > 0) result.append(',');
            result.append(vector[i]);
        }
        if (!nonzero) throw new IllegalArgumentException("Zero embedding has no cosine distance");
        return result.append(']').toString();
    }
}
