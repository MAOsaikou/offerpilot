package com.offerpilot.knowledge.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

/** Citable hybrid reference retrieval over curated entries; planned reports are excluded. */
@Service
public class BuiltinKnowledgeSearch {
    private final JdbcTemplate jdbc;
    private final OccupationEmbeddings embeddings;
    public BuiltinKnowledgeSearch(JdbcTemplate jdbc,OccupationEmbeddings embeddings) { this.jdbc=jdbc;this.embeddings=embeddings; }
    public record Hit(String id,String kind,String title,String excerpt,String source,String notice,double score,boolean lexicalMatch,boolean vectorMatch) {}
    public record Result(List<Hit> items,boolean vectorAvailable,String notice) {}
    public Result search(String query,String kind,int limit) {
        String q=BuiltinKnowledgeRepository.clean(query,2000);
        var filters=new BuiltinKnowledgeRepository.Filters(kind,q,"","","","",1,limit);
        if(q.isBlank()) throw BuiltinKnowledgeRepository.invalid();
        String lexical=OccupationDocument.lexicalQuery(q), category=filters.kind();
        int candidates=Math.min(200,limit*4);
        var fts=lexical.isBlank()?List.<Hit>of():jdbc.query("""
            SELECT id,kind,title,content,source,notice FROM builtin_knowledge_entries,to_tsquery('simple',?) q
            WHERE status='READY' AND (?='' OR kind=?) AND tsv @@ q ORDER BY ts_rank_cd(tsv,q) DESC,id LIMIT ?
            """,(r,n) -> new Hit(r.getString(1),r.getString(2),r.getString(3),KnowledgeNoteSearch.bounded(r.getString(4),1000),r.getString(5),r.getString(6),0,true,false),lexical,category,category,candidates);
        List<Hit> vectors=List.of();String fingerprint=embeddings.fingerprint();
        if(BuiltinKnowledgeIndex.local(fingerprint)&&embeddings.configured()
            &&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM builtin_knowledge_entries WHERE status='READY' AND embedding IS NOT NULL AND embedding_fingerprint=? AND (?='' OR kind=?))",Boolean.class,fingerprint,category,category))) {
            var value=embeddings.embed(List.of(q));
            if(value.size()==1) vectors=jdbc.query("""
                SELECT id,kind,title,content,source,notice FROM builtin_knowledge_entries
                WHERE status='READY' AND embedding_fingerprint=? AND embedding IS NOT NULL AND (?='' OR kind=?)
                ORDER BY embedding <=> ?::vector,id LIMIT ?
                """,(r,n) -> new Hit(r.getString(1),r.getString(2),r.getString(3),KnowledgeNoteSearch.bounded(r.getString(4),1000),r.getString(5),r.getString(6),0,false,true),fingerprint,category,category,OccupationEmbeddings.literal(value.get(0)),candidates);
        }
        return rank(fts,vectors,limit);
    }
    static Result rank(List<Hit> fts,List<Hit> vectors,int limit) {
        boolean available=!vectors.isEmpty();
        var scores=OccupationRrf.weighted(new double[]{1,available?OccupationRrf.LEXICAL_WITH_VECTOR:1},vectors.stream().map(Hit::id).toList(),fts.stream().map(Hit::id).toList());
        var all=new LinkedHashMap<String,Hit>();vectors.forEach(h -> all.put(h.id(),h));
        fts.forEach(h -> all.merge(h.id(),h,(v,l) -> new Hit(l.id(),l.kind(),l.title(),l.excerpt(),l.source(),l.notice(),0,true,true)));
        var hits=all.values().stream().map(h -> new Hit(h.id(),h.kind(),h.title(),h.excerpt(),h.source(),h.notice(),scores.get(h.id()),h.lexicalMatch(),h.vectorMatch()))
            .sorted(Comparator.comparingDouble(Hit::score).reversed().thenComparing(Hit::id)).limit(limit).toList();
        return new Result(hits,available,available?"本地 bge-m3 + 全文 RRF；分数非置信度，引用仅为练习资料。":"本地向量未就绪，当前仅全文检索；引用仅为练习资料。");
    }
}
