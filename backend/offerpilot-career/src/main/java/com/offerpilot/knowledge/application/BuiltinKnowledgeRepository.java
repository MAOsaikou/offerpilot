package com.offerpilot.knowledge.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

/** Read-only public catalog. Dynamic SQL uses fixed columns and bound user values only. */
@Repository
public class BuiltinKnowledgeRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public BuiltinKnowledgeRepository(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc;this.json=json; }
    public record Page<T>(List<T> items,long total,int page,int size) {}
    public record Occupation(String id,String name,String family,String description,String hollandCode,JsonNode riasec,
        JsonNode coreSkills,JsonNode salaryRange,String salaryNotice,String growthPath,String growthPathSource,
        String skillsSource,String onetCode,String source,String sourceUrl,String license,String attribution,String notice) {}
    public record Entry(String id,String kind,String title,String content,String industry,String role,String questionType,
        String difficulty,String source,String sourceUrl,String license,String notice,String status,JsonNode payload) {}
    public record Facets(List<String> families,List<String> industries,List<String> roles,Map<String,Long> counts,long occupations,long directOnetOccupations) {}
    public record Filters(String kind,String q,String industry,String role,String questionType,String difficulty,int page,int size) {
        public Filters {
            kind=clean(kind,30); q=clean(q,2000);industry=clean(industry,100);role=clean(role,100);
            questionType=clean(questionType,30); difficulty=clean(difficulty,30); bounds(page,size);
            member(kind,Set.of("QUESTION","COMPETENCY","CAMPUS","REPORT"));
            member(questionType,Set.of("BEHAVIORAL","SITUATIONAL","KNOWLEDGE","CASE"));
            member(difficulty,Set.of("EASY","STANDARD","HARD"));
        }
    }
    public static String clean(String s,int max) {
        if(s==null) return "";
        if(s.length()>max) throw invalid(); return s.strip();
    }
    private static void member(String s,Set<String> values) { if(!s.isEmpty()&&!values.contains(s)) throw invalid(); }
    static BusinessException invalid() { return new BusinessException(ErrorCode.VALIDATION_FAILED,"请检查分类、查询长度与分页范围（page 1–100000，size 1–100）。"); }
    public static void bounds(int page,int size) { if(page<1||page>100000||size<1||size>100) throw invalid(); }
    private JsonNode node(String text) {
        try { return text==null?null:json.readTree(text); } catch(java.io.IOException e) { throw new IllegalStateException("Invalid catalog JSON",e); }
    }
    private Occupation mapOccupation(ResultSet r,int n) throws SQLException {
        return new Occupation(r.getString("id"),r.getString("name"),r.getString("family"),r.getString("description"),r.getString("holland_code"),
            node(r.getString("holland_scores")),node(r.getString("core_skills")),node(r.getString("salary_range")),
            r.getString("salary_range")==null?"暂无数据：尚无可信薪资来源":"请核对薪资来源及统计范围",
            r.getString("growth_path"),r.getString("growth_path_source"),r.getString("skills_source"),r.getString("onet_code"),
            r.getString("source"),r.getString("source_url"),r.getString("license"),r.getString("attribution"),r.getString("content_notice"));
    }
    private Entry mapEntry(ResultSet r,int n) throws SQLException {
        return new Entry(r.getString("id"),r.getString("kind"),r.getString("title"),r.getString("content"),r.getString("industry"),r.getString("role"),
            r.getString("question_type"),r.getString("difficulty"),r.getString("source"),r.getString("source_url"),r.getString("license"),r.getString("notice"),r.getString("status"),node(r.getString("payload")));
    }
    public Page<Occupation> occupations(String query,String family,String holland,int page,int size) {
        bounds(page,size);String q=clean(query,2000),f=clean(family,100),h=clean(holland,3).toUpperCase(Locale.ROOT);
        if(!h.matches("[RIASEC]{0,3}")) throw invalid();
        var args=new ArrayList<Object>();StringBuilder where=new StringBuilder(" WHERE 1=1");
        if(!q.isEmpty()) { where.append(" AND (strpos(lower(name||' '||description||' '||array_to_string(aliases,' ')),lower(?))>0 OR tsv @@ to_tsquery('simple',?))");args.add(q);args.add(OccupationDocument.lexicalQuery(q).isBlank()?"'__no_token__'":OccupationDocument.lexicalQuery(q)); }
        if(!f.isEmpty()) { where.append(" AND family=?");args.add(f); }
        if(!h.isEmpty()) { where.append(" AND holland_code LIKE ?");args.add(h+"%"); }
        long total=jdbc.queryForObject("SELECT count(*) FROM occupations"+where,Long.class,args.toArray());
        args.add(size);args.add((page-1)*size);
        return new Page<>(jdbc.query("SELECT * FROM occupations"+where+" ORDER BY name,id LIMIT ? OFFSET ?",this::mapOccupation,args.toArray()),total,page,size);
    }
    public Occupation occupation(String id) {
        return jdbc.query("SELECT * FROM occupations WHERE id=?",this::mapOccupation,id).stream().findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_NOTE_NOT_FOUND,"知识库条目不存在"));
    }
    public Page<Entry> entries(Filters f) {
        var args=new ArrayList<Object>();StringBuilder where=new StringBuilder(" WHERE 1=1");
        String[] cols={"kind","industry","role","question_type","difficulty"};String[] vals={f.kind(),f.industry(),f.role(),f.questionType(),f.difficulty()};
        for(int i=0;i<cols.length;i++) if(!vals[i].isEmpty()) {where.append(" AND ").append(cols[i]).append("=?");args.add(vals[i]);}
        if(!f.q().isEmpty()) { where.append(" AND (strpos(lower(title||' '||content),lower(?))>0 OR tsv @@ to_tsquery('simple',?))");args.add(f.q());String q=OccupationDocument.lexicalQuery(f.q());args.add(q.isBlank()?"'__no_token__'":q); }
        long total=jdbc.queryForObject("SELECT count(*) FROM builtin_knowledge_entries"+where,Long.class,args.toArray());
        args.add(f.size());args.add((f.page()-1)*f.size());
        return new Page<>(jdbc.query("SELECT * FROM builtin_knowledge_entries"+where+" ORDER BY id LIMIT ? OFFSET ?",this::mapEntry,args.toArray()),total,f.page(),f.size());
    }
    public Entry entry(String id) {
        return jdbc.query("SELECT * FROM builtin_knowledge_entries WHERE id=?",this::mapEntry,id).stream().findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCode.KNOWLEDGE_NOTE_NOT_FOUND,"知识库条目不存在"));
    }
    public Facets facets() {
        var counts=new LinkedHashMap<String,Long>();
        jdbc.query("SELECT kind,count(*) FROM builtin_knowledge_entries GROUP BY kind ORDER BY kind",r -> {counts.put(r.getString(1),r.getLong(2));});
        return new Facets(jdbc.queryForList("SELECT DISTINCT family FROM occupations ORDER BY family",String.class),
            jdbc.queryForList("SELECT DISTINCT industry FROM builtin_knowledge_entries WHERE industry<>'' ORDER BY industry",String.class),
            jdbc.queryForList("SELECT DISTINCT role FROM builtin_knowledge_entries WHERE role<>'' ORDER BY role",String.class),counts,
            jdbc.queryForObject("SELECT count(*) FROM occupations",Long.class),jdbc.queryForObject("SELECT count(*) FROM occupations WHERE source='onet'",Long.class));
    }
}
