package com.offerpilot;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Test-only hash feature vectors. Lexical similarity, not a simulation of model semantics. */
@TestConfiguration
@Profile("mock-llm")
class OccupationMockEmbeddingConfiguration {
    @Bean EmbeddingModel occupationMockEmbeddingModel() {
        return new EmbeddingModel() {
            @Override public float[] embed(Document document) { return hash(document.getText()); }
            @Override public EmbeddingResponse call(EmbeddingRequest request) {
                List<Embedding> result = new ArrayList<>();
                for (int i = 0; i < request.getInstructions().size(); i++)
                    result.add(new Embedding(hash(request.getInstructions().get(i)), i));
                return new EmbeddingResponse(result);
            }
        };
    }

    static float[] hash(String text) {
        float[] vector = new float[1024];
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        for (int i = 0; i < normalized.length() - 1; i++) {
            String feature = normalized.substring(i, i + 2);
            if (!feature.isBlank()) vector[Math.floorMod(feature.hashCode(), 1024)] += 1;
        }
        vector[Math.floorMod(normalized.hashCode(), 1024)] += 1;
        return vector;
    }
}
