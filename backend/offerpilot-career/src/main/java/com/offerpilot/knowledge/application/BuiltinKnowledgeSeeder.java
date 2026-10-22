package com.offerpilot.knowledge.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Materialized editorial seeds; startup never generates content or calls models. */
@Component
@Order(11)
public class BuiltinKnowledgeSeeder implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public BuiltinKnowledgeSeeder(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    @Override @Transactional public void run(ApplicationArguments args) throws java.io.IOException {
        try (var in=new ClassPathResource("seed/builtin-knowledge.json").getInputStream()) {
            var entries=json.readTree(in).path("entries");
            if (!entries.isArray() || entries.isEmpty()) throw new IllegalStateException("Missing builtin knowledge seed");
            for (var e:entries) {
                String document=e.path("title").asText()+"\n"+e.path("content").asText()+"\n"+e.path("industry").asText()+"\n"+e.path("role").asText();
                jdbc.update("""
                    INSERT INTO builtin_knowledge_entries(id,kind,title,content,industry,role,question_type,difficulty,
                        source,source_url,license,notice,status,payload,tsv)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,to_tsvector('simple',?))
                    ON CONFLICT(id) DO UPDATE SET kind=EXCLUDED.kind,title=EXCLUDED.title,content=EXCLUDED.content,
                        industry=EXCLUDED.industry,role=EXCLUDED.role,question_type=EXCLUDED.question_type,
                        difficulty=EXCLUDED.difficulty,source=EXCLUDED.source,source_url=EXCLUDED.source_url,
                        license=EXCLUDED.license,notice=EXCLUDED.notice,status=EXCLUDED.status,payload=EXCLUDED.payload,
                        tsv=EXCLUDED.tsv,embedding=NULL,embedding_fingerprint=NULL,
                        revision=builtin_knowledge_entries.revision+1,updated_at=now()
                    WHERE (builtin_knowledge_entries.kind,builtin_knowledge_entries.title,builtin_knowledge_entries.content,
                        builtin_knowledge_entries.industry,builtin_knowledge_entries.role,builtin_knowledge_entries.question_type,
                        builtin_knowledge_entries.difficulty,builtin_knowledge_entries.source,builtin_knowledge_entries.source_url,
                        builtin_knowledge_entries.license,builtin_knowledge_entries.notice,builtin_knowledge_entries.status,builtin_knowledge_entries.payload)
                    IS DISTINCT FROM (EXCLUDED.kind,EXCLUDED.title,EXCLUDED.content,EXCLUDED.industry,EXCLUDED.role,
                        EXCLUDED.question_type,EXCLUDED.difficulty,EXCLUDED.source,EXCLUDED.source_url,
                        EXCLUDED.license,EXCLUDED.notice,EXCLUDED.status,EXCLUDED.payload)
                    """,e.path("id").asText(),e.path("kind").asText(),e.path("title").asText(),e.path("content").asText(),
                        e.path("industry").asText(),e.path("role").asText(),e.path("questionType").asText(null),e.path("difficulty").asText(null),
                        e.path("source").asText(),e.path("sourceUrl").asText(null),e.path("license").asText(),e.path("notice").asText(),
                        e.path("status").asText(),e.path("payload").toString(),OccupationDocument.fulltext(document));
            }
        }
    }
}
