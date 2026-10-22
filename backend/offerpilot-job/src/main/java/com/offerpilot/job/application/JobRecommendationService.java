package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.common.error.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;

@Service
public class JobRecommendationService {
    private final JdbcTemplate jdbc; private final ObjectMapper json; private final JobRecommendationContext context;
    private final JobLocalSearch local; private final JobService jobs;
    public JobRecommendationService(JdbcTemplate jdbc,ObjectMapper json,JobRecommendationContext context,JobLocalSearch local,JobService jobs) {
        this.jdbc=jdbc; this.json=json; this.context=context; this.local=local; this.jobs=jobs;
    }
    private com.offerpilot.common.notification.NotificationSink notices;
    @org.springframework.beans.factory.annotation.Autowired
    public void setNotifications(com.offerpilot.common.notification.NotificationSink notices) { this.notices=notices; }
    @Transactional
    public JsonNode daily(String owner) {
        if(jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",String.class,owner).isEmpty()) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        var signals=context.read(owner); String day=LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        String catalog=jdbc.queryForObject("SELECT count(*)::text || ':' || coalesce(max(updated_at)::text,'empty') FROM jobs WHERE (user_id IS NULL OR user_id=?) AND coalesce(payload->>'source','') NOT IN ('demo','LOCAL_SNAPSHOT')",String.class,owner);
        String hash=hash(signals.toString()+catalog);
        var cached=jdbc.queryForList("SELECT snapshot::text FROM job_daily_recommendations WHERE user_id=? AND day=?::date AND context_hash=?",String.class,owner,day,hash);
        ObjectNode result;
        if(!cached.isEmpty()) { result=parse(cached.get(0)); result.put("cached",true); }
        else {
            List<JsonNode> candidates=local.read("SELECT id,payload,detail_fetched_at,updated_at FROM jobs WHERE (user_id IS NULL OR user_id=?) AND coalesce(payload->>'source','') NOT IN ('demo','LOCAL_SNAPSHOT') ORDER BY updated_at DESC,id LIMIT 1000",List.of(owner));
            result=json.createObjectNode().put("date",day).put("cached",false).put("rankingVersion","lexical-v1").put("generatedAt",Instant.now().toString());
            var items=result.putArray("items"); rank(signals,candidates).stream().limit(20).forEach(items::add);
            jdbc.update("INSERT INTO job_daily_recommendations(user_id,day,context_hash,snapshot) VALUES (?,?::date,?,?::jsonb) ON CONFLICT(user_id) DO UPDATE SET day=EXCLUDED.day,context_hash=EXCLUDED.context_hash,snapshot=EXCLUDED.snapshot",owner,day,hash,result.toString());
        }
        // Cache stores IDs and reasons, never copies a private JD/resume. Enforce current visibility.
        var visible=json.createArrayNode();
        for(var item:result.path("items")) try {
            var job=jobs.get(owner,item.path("jobId").asText());
            if(expired(job) || Set.of("demo","LOCAL_SNAPSHOT").contains(job.path("source").asText())) continue;
            var entry=(ObjectNode)item.deepCopy(); entry.set("job",job); visible.add(entry);
        } catch(BusinessException ex) { if(ex.errorCode()!=ErrorCode.JOB_NOT_FOUND) throw ex; }
        result.set("items",visible);
        if(notices!=null) for(var item:visible) notices.send(owner,"NEW_JOB","recommended-job:"+item.path("jobId").asText(),
            "发现匹配你画像的岗位",item.path("job").path("title").asText()+" · "+item.path("job").path("company").asText(),"/jobs/"+item.path("jobId").asText());
        return result;
    }
    static List<ObjectNode> rank(JobRecommendationContext.Context context,List<JsonNode> jobs) {
        var json=new ObjectMapper(); var ranked=new ArrayList<ObjectNode>();
        for(var job:jobs) {
            if(expired(job) || Set.of("demo","LOCAL_SNAPSHOT").contains(job.path("source").asText())) continue;
            String title=job.path("title").asText()+" "+job.path("category").asText();
            String document=title+" "+job.path("description").asText()+" "+job.path("requirement").asText()+" "+job.path("skills").toString();
            var reasons=new ArrayList<String>();
            double score=contribution(context.target(),title,40,"目标职业",reasons)
                    +contribution(context.profileSkills(),document,25,"画像技能/专业",reasons)
                    +contribution(context.resumeSkills(),document,20,"保存简历",reasons);
            if(!context.city().isBlank() && job.path("locations").asText().contains(context.city())) { score+=15; reasons.add("符合目标城市："+context.city()); }
            if(score<=0) continue;
            var item=json.createObjectNode().put("jobId",job.path("id").asText()).put("score",Math.round(score));
            var why=item.putArray("reasons"); reasons.forEach(why::add); ranked.add(item);
        }
        return ranked.stream().sorted(Comparator.<ObjectNode>comparingInt(n->n.path("score").asInt()).reversed().thenComparing(n->n.path("jobId").asText())).toList();
    }
    private static double contribution(String signal,String document,int weight,String label,List<String> reasons) {
        var tokens=JobKeywords.tokens(signal); String lower=document.toLowerCase(Locale.ROOT);
        var hits=tokens.stream().filter(lower::contains).limit(8).toList();
        if(hits.isEmpty()) return 0;
        reasons.add(label+"匹配："+String.join("、",hits));
        return weight*Math.min(1.0,(double)hits.size()/Math.max(1,tokens.size()));
    }
    static boolean expired(JsonNode job) {
        try { return LocalDate.parse(job.path("deadline").asText()).isBefore(LocalDate.now(ZoneId.of("Asia/Shanghai"))); }
        catch(java.time.format.DateTimeParseException ex) { return false; }
    }
    private static String hash(String value) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private ObjectNode parse(String value) { try { return (ObjectNode)json.readTree(value); } catch(java.io.IOException ex) { throw new IllegalStateException(ex); } }
}
