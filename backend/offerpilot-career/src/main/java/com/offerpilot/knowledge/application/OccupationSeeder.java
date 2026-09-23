package com.offerpilot.knowledge.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 把内置职业条目库（classpath:seed/occupations*.json）同步进 occupations 表。
 * 目前有两个来源：occupations.json（人工维护的中文条目）与 occupations-onet.json（O*NET 批量导入）。
 *
 * 四个设计决定：
 * - 幂等：按 id upsert，重复启动既不报错也不产生重复行。
 * - 以文件为准：内置条目每次启动都会覆盖，直接在库里改的内容会被还原；
 *   将来若要支持人工编辑，需要加一列区分「内置」和「人工修订」。
 * - 不做向量化：embedding 与 tsv 留空，等 RAG 阶段由独立入库任务填充。
 *   职业推荐的规则层只用 holland_code / holland_scores / core_skills，不依赖向量。
 * - 多文件：人工维护的条目和脚本生成的条目分开存放，避免重新生成时覆盖手工内容。
 */
@Component
public class OccupationSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(OccupationSeeder.class);
    private static final String UPSERT = """
            INSERT INTO occupations(id, name, aliases, family, description, holland_code, holland_scores,
                                    core_skills, typical_titles, growth_path, salary_range, outlook,
                                    companies, related_majors, source, updated_at)
            VALUES (?,?,CAST(? AS text[]),?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),CAST(? AS text[]),?,
                    CAST(? AS jsonb),?,CAST(? AS text[]),CAST(? AS text[]),?,now())
            ON CONFLICT (id) DO UPDATE SET
                name=EXCLUDED.name, aliases=EXCLUDED.aliases, family=EXCLUDED.family,
                description=EXCLUDED.description, holland_code=EXCLUDED.holland_code,
                holland_scores=EXCLUDED.holland_scores, core_skills=EXCLUDED.core_skills,
                typical_titles=EXCLUDED.typical_titles, growth_path=EXCLUDED.growth_path,
                salary_range=EXCLUDED.salary_range, outlook=EXCLUDED.outlook,
                companies=EXCLUDED.companies, related_majors=EXCLUDED.related_majors,
                source=EXCLUDED.source, updated_at=now()
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Resource[] seeds;

    public OccupationSeeder(JdbcTemplate jdbc, ObjectMapper json,
                            @Value("classpath*:seed/occupations*.json") Resource[] seeds) {
        this.jdbc = jdbc;
        this.json = json;
        this.seeds = seeds;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws IOException {
        int total = 0;
        for (Resource seed : seeds) {
            List<JsonNode> entries = readEntries(seed);
            entries.forEach(this::upsert);
            total += entries.size();
            log.info("职业条目已同步 {}：{} 条", seed.getFilename(), entries.size());
        }
        if (total == 0) throw new IllegalStateException("没有加载到任何职业条目，请检查 seed/occupations*.json");
        log.info("职业条目库同步完成，共 {} 条", total);
    }

    private void upsert(JsonNode entry) {
        jdbc.update(UPSERT,
                required(entry, "id"),
                required(entry, "name"),
                arrayOf(entry, "aliases"),
                required(entry, "family"),
                required(entry, "description"),
                text(entry, "hollandCode"),
                jsonOf(entry, "hollandScores"),
                jsonOf(entry, "coreSkills"),
                arrayOf(entry, "typicalTitles"),
                text(entry, "growthPath"),
                jsonOf(entry, "salaryRange"),
                text(entry, "outlook"),
                arrayOf(entry, "companies"),
                arrayOf(entry, "relatedMajors"),
                required(entry, "source"));
    }

    private List<JsonNode> readEntries(Resource seed) throws IOException {
        try (InputStream in = seed.getInputStream()) {
            JsonNode items = json.readTree(in).path("occupations");
            if (!items.isArray() || items.isEmpty()) {
                throw new IllegalStateException(seed.getFilename() + " 缺少 occupations 数组");
            }
            List<JsonNode> entries = new ArrayList<>();
            items.forEach(entries::add);
            return entries;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String required(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("职业条目 " + node.path("id").asText("<无 id>")
                    + " 缺少必填字段 " + field);
        }
        return value;
    }

    /** jsonb 列直接传 JSON 文本，交给数据库解析。 */
    private static String jsonOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.toString();
    }

    /**
     * 生成 PostgreSQL 数组字面量。
     *
     * 每个元素都加双引号并转义反斜杠与引号，这样元素里出现逗号、括号或引号也不会破坏结构。
     * 例：["Java", "C++"] -> {"Java","C++"}
     */
    private static String arrayOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isArray() || value.isEmpty()) return "{}";
        StringBuilder literal = new StringBuilder("{");
        for (int i = 0; i < value.size(); i++) {
            if (i > 0) literal.append(',');
            literal.append('"')
                    .append(value.get(i).asText().replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        return literal.append('}').toString();
    }
}
