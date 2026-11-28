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
    private static final Set<Character> DANGLING_CHARS = Set.of('与', '和', '或', '及', '的', '在', '于', '已', '等');

    private static double contribution(String signal,String document,int weight,String label,List<String> reasons) {
        var tokens=JobKeywords.tokens(signal); String lower=document.toLowerCase(Locale.ROOT);
        var hits=tokens.stream().filter(lower::contains).limit(8).toList();
        if(hits.isEmpty()) return 0;
        reasons.add(label+"匹配："+String.join("、",mergeHits(hits,signal,document)));
        return weight*Math.min(1.0,(double)hits.size()/Math.max(1,tokens.size()));
    }
    static List<String> mergeHits(List<String> hits,String signal,String document) {
        if(hits==null || hits.isEmpty()) return List.of();
        String sigLower=signal.toLowerCase(Locale.ROOT);
        String docLower=document.toLowerCase(Locale.ROOT);
        var merged=new ArrayList<String>();
        for(String hit:hits) {
            if(merged.isEmpty()) { merged.add(hit); continue; }
            int lastIdx=merged.size()-1;
            String prev=merged.get(lastIdx);
            if(prev.contains(hit)) continue;
            String combined=tryCombine(prev,hit,sigLower,docLower);
            if(combined!=null) merged.set(lastIdx,combined);
            else merged.add(hit);
        }
        var clean=new ArrayList<String>();
        for(String item:merged) {
            String sanitized=sanitize(item,sigLower,docLower);
            if(!isMeaningful(sanitized,sigLower)) continue;
            boolean subsumed=false;
            for(int i=0;i<clean.size();i++) {
                String existing=clean.get(i);
                if(existing.equals(sanitized) || existing.contains(sanitized)) { subsumed=true; break; }
                if(sanitized.contains(existing)) { clean.set(i,sanitized); subsumed=true; break; }
            }
            if(!subsumed) clean.add(sanitized);
        }
        return clean.isEmpty()?hits:clean;
    }
    private static String tryCombine(String prev,String hit,String sigLower,String docLower) {
        int maxOverlap=Math.min(prev.length(),hit.length());
        for(int k=maxOverlap;k>=1;k--) {
            if(prev.endsWith(hit.substring(0,k))) {
                String cand=prev+hit.substring(k);
                if(sigLower.contains(cand) && docLower.contains(cand)) return cand;
            }
        }
        String cand=prev+hit;
        if(sigLower.contains(cand) && docLower.contains(cand)) return cand;
        return null;
    }
    private static String sanitize(String s,String sigLower,String docLower) {
        if(s==null || s.isBlank()) return "";
        while(!s.isEmpty() && DANGLING_CHARS.contains(s.charAt(0))) s=s.substring(1);
        while(!s.isEmpty() && DANGLING_CHARS.contains(s.charAt(s.length()-1))) s=s.substring(0,s.length()-1);
        if(s.length()==3 && Character.UnicodeScript.of(s.codePointAt(0))==Character.UnicodeScript.HAN) {
            String prefix=s.substring(0,2);
            int idx=sigLower.indexOf(s);
            if(idx>=0 && idx+3<sigLower.length() && Character.UnicodeScript.of(sigLower.codePointAt(idx+3))==Character.UnicodeScript.HAN) {
                if(sigLower.contains(prefix) && docLower.contains(prefix)) s=prefix;
            }
            if(s.length()==3) {
                String suffix=s.substring(1);
                idx=sigLower.indexOf(s);
                if(idx>0 && Character.UnicodeScript.of(sigLower.codePointAt(idx-1))==Character.UnicodeScript.HAN) {
                    if(sigLower.contains(suffix) && docLower.contains(suffix)) s=suffix;
                }
            }
        }
        return s;
    }
    private static boolean isMeaningful(String s,String sigLower) {
        if(s==null || s.isBlank()) return false;
        if(Character.UnicodeScript.of(s.codePointAt(0))==Character.UnicodeScript.HAN && s.length()<2) return false;
        if(s.length()<=2) {
            char first=s.charAt(0); char last=s.charAt(s.length()-1);
            if(DANGLING_CHARS.contains(first) || DANGLING_CHARS.contains(last)) return false;
            if(isStrictlyInternal(s,sigLower)) return false;
        }
        return true;
    }
    private static boolean isStrictlyInternal(String s,String sigLower) {
        if(s.length()!=2) return false;
        if(Character.UnicodeScript.of(s.codePointAt(0))!=Character.UnicodeScript.HAN) return false;
        int idx=0; boolean foundAny=false;
        while((idx=sigLower.indexOf(s,idx))>=0) {
            foundAny=true;
            boolean hasHanBefore=idx>0 && Character.UnicodeScript.of(sigLower.codePointAt(idx-1))==Character.UnicodeScript.HAN;
            boolean hasHanAfter=idx+2<sigLower.length() && Character.UnicodeScript.of(sigLower.codePointAt(idx+2))==Character.UnicodeScript.HAN;
            if(!hasHanBefore || !hasHanAfter) return false;
            idx+=1;
        }
        return foundAny;
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
