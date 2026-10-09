package com.offerpilot.knowledge.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/** Optional provider: missing credentials/model and supplier failures never disable Holland rules. */
@Component
public class OccupationEmbeddings {
    private static final Logger log = LoggerFactory.getLogger(OccupationEmbeddings.class);
    private final ObjectProvider<EmbeddingModel> models;
    private final Environment env;

    public OccupationEmbeddings(ObjectProvider<EmbeddingModel> models, Environment env) {
        this.models = models;
        this.env = env;
    }

    public List<float[]> embed(List<String> documents) {
        boolean mock = Arrays.asList(env.getActiveProfiles()).contains("mock-llm");
        String key = env.getProperty("spring.ai.openai.api-key", "");
        if (!mock && ("none".equals(env.getProperty("spring.ai.model.embedding"))
                || key.isBlank() || "sk-not-configured".equals(key))) {
            log.warn("职业向量不可用：embedding 已关闭或未配置 Bailian Key；保留全文索引与规则推荐");
            return List.of();
        }
        try {
            EmbeddingModel model = models.getIfAvailable();
            if (model == null) {
                log.warn("职业向量不可用：无 EmbeddingModel；保留规则推荐");
                return List.of();
            }
            var vectors = model.embed(documents);
            if (vectors.size() != documents.size()) throw new IllegalArgumentException("Embedding batch size mismatch");
            vectors.forEach(OccupationEmbeddings::literal);
            return vectors;
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
