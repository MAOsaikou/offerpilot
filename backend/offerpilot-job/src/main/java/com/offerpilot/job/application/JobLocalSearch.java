package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class JobLocalSearch {
    public record Result(List<JsonNode> items,boolean vectorAvailable) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JobEmbeddings embeddings;
    public JobLocalSearch(JdbcTemplate jdbc,ObjectMapper json,JobEmbeddings embeddings) { this.jdbc=jdbc; this.json=json; this.embeddings=embeddings; }
    static final String TEXT="lower(concat_ws(' ',payload->>'title',payload->>'company',payload->>'category',payload->>'description',payload->>'requirement',payload->>'skills'))";
    public Result search(String owner,JobSearchParser.Criteria criteria) {
        String filters="(user_id IS NULL OR user_id=?) AND (?='all' OR nature=?) AND ((?<>'' AND site=?) OR (?='' AND (?='' OR payload->>'company'=?))) AND (?='' OR position(? in coalesce(payload->>'locations',''))>0)";
        var params=new ArrayList<Object>(Arrays.asList(owner,criteria.nature(),criteria.nature(),criteria.site(),criteria.site(),criteria.site(),criteria.company(),criteria.company(),criteria.location(),criteria.location()));
        var terms=JobKeywords.tokens(criteria.query());
        var tokens=terms.isEmpty() && !criteria.query().isBlank()?List.of(criteria.query().toLowerCase(Locale.ROOT)):terms.stream().limit(12).toList();
        String lexical=String.join(" OR ",tokens.stream().map(t->"position(? in "+TEXT+")>0").toList());
        String relevance=String.join(" + ",tokens.stream().map(t->"(CASE WHEN position(? in lower(coalesce(payload->>'title','')))>0 THEN 4 ELSE 0 END + CASE WHEN position(? in "+TEXT+")>0 THEN 1 ELSE 0 END)").toList());
        var lexicalArgs=new ArrayList<Object>();
        if(!relevance.isBlank()) tokens.forEach(t->{ lexicalArgs.add(t); lexicalArgs.add(t); });
        lexicalArgs.addAll(params); lexicalArgs.addAll(tokens);
        List<JsonNode> words=read("SELECT id,payload,detail_fetched_at,updated_at"+(relevance.isBlank()?"":",("+relevance+") AS lexical_score")+" FROM jobs WHERE "+filters+(lexical.isBlank()?"":" AND ("+lexical+")")+" ORDER BY "+(relevance.isBlank()?"":"lexical_score DESC,")+"updated_at DESC,id LIMIT 60",lexicalArgs);
        List<JsonNode> vectors=List.of();
        String vector=null;
        try {
        if(embeddings.enabled() && !criteria.query().isBlank()) vector=embeddings.embed(criteria.query());
        if(vector!=null) {
            var args=new ArrayList<>(params); args.add(embeddings.key()); args.add(vector);
            vectors=read("SELECT id,payload,detail_fetched_at,updated_at FROM jobs WHERE "+filters+" AND search_embedding IS NOT NULL AND embedding_key=? AND embedding_hash=md5(payload::text) ORDER BY search_embedding <=> ?::vector,id LIMIT 60",args);
        }
        } catch(RuntimeException ex) { vector=null; vectors=List.of(); }
        var merged=new LinkedHashMap<String,JsonNode>(); var scores=new HashMap<String,Double>();
        for(var list:List.of(words,vectors)) for(int i=0;i<list.size();i++) {
            var job=list.get(i); String id=job.path("id").asText(); merged.putIfAbsent(id,job); scores.merge(id,1.0/(60+i+1),Double::sum);
        }
        var ranked=merged.values().stream().sorted(Comparator.<JsonNode>comparingDouble(j->scores.get(j.path("id").asText())).reversed().thenComparing(j->j.path("id").asText())).limit(30).toList();
        return new Result(ranked,vector!=null && !vectors.isEmpty());
    }
    List<JsonNode> read(String sql,List<Object> args) {
        RowMapper<JsonNode> mapper=(rs,n)-> {
            try {
                var node=(ObjectNode)json.readTree(rs.getString("payload")); node.put("id",rs.getString("id"));
                var fetched=rs.getTimestamp("detail_fetched_at"); if(fetched==null) node.putNull("detailFetchedAt"); else node.put("detailFetchedAt",fetched.toInstant().toString());
                node.put("storedAt",rs.getTimestamp("updated_at").toInstant().toString()); node.put("retrievalSource","LOCAL_DB"); return node;
            } catch(java.io.IOException ex) { throw new IllegalStateException(ex); }
        };
        return jdbc.query(connection -> {
            var statement=connection.prepareStatement(sql); statement.setQueryTimeout(1);
            for(int i=0;i<args.size();i++) statement.setObject(i+1,args.get(i)); return statement;
        },mapper);
    }
}
