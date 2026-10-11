package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.id.IdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

@Service
public class JobService implements JobDescriptionForChat {
    private final JobSource source;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public JobService(JobSource source, JdbcTemplate jdbc, ObjectMapper json) { this.source=source; this.jdbc=jdbc; this.json=json; }
    public JsonNode sites() { return source.sites(); }
    public JsonNode search(String query, String site, String nature, String location) {
        if (query == null || query.isBlank() || query.length()>100 || site.length()>60 || location.length()>60
                || !Set.of("campus","intern","social","all").contains(nature)) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        Map<String,Object> args = new LinkedHashMap<>();
        args.put("query",query.strip()); args.put("nature",nature); args.put("limit",30);
        if (!site.isBlank()) args.put("site",site);
        if (!location.isBlank()) args.put("location",location.strip());
        JsonNode result = source.search(args);
        var output=json.createObjectNode(); var items=output.putArray("items"); Set<String> seen=new HashSet<>();
        for (var raw:result.path("jobs")) {
            if (raw.path("id").asText().isBlank() || raw.path("site").asText().isBlank() || raw.path("title").asText().isBlank()) continue;
            String actualNature=Set.of("campus","intern","social").contains(raw.path("nature").asText()) ? raw.path("nature").asText() : nature;
            String key=raw.path("site").asText()+":"+raw.path("id").asText()+":"+actualNature;
            if (seen.add(key)) items.add(save(raw,actualNature));
        }
        output.set("meta",result.path("_meta")); output.put("fetchedAt",Instant.now().toString());
        return output;
    }
    private JsonNode save(JsonNode raw,String nature) {
        String id=IdGenerator.next("job");
        ObjectNode data=normalize(raw,nature);
        return jdbc.queryForObject("INSERT INTO jobs(id,site,external_id,nature,payload) VALUES (?,?,?,?,?::jsonb) ON CONFLICT(site,external_id,nature) DO UPDATE SET payload=CASE WHEN jobs.detail_fetched_at IS NULL THEN EXCLUDED.payload ELSE jobs.payload END,updated_at=now() RETURNING id,payload,detail_fetched_at",
            (rs,n)->view(rs.getString("id"),rs.getString("payload"),rs.getTimestamp("detail_fetched_at")),
            id,data.path("site").asText(),data.path("externalId").asText(),nature,data.toString());
    }
    public JsonNode get(String id) {
        var rows=jdbc.query("SELECT id,payload,detail_fetched_at FROM jobs WHERE id=?",(rs,n)->view(rs.getString("id"),rs.getString("payload"),rs.getTimestamp("detail_fetched_at")),id);
        if(rows.isEmpty()) throw new BusinessException(ErrorCode.JOB_NOT_FOUND);
        return rows.get(0);
    }
    public JsonNode detail(String id,boolean force) {
        var existing=get(id);
        if(!force && !existing.path("detailFetchedAt").isNull() && Instant.parse(existing.path("detailFetchedAt").asText()).isAfter(Instant.now().minusSeconds(600))) return existing;
        if(source.localOnly() || "demo".equals(existing.path("site").asText())
                || "demo".equals(existing.path("source").asText())
                || "LOCAL_SNAPSHOT".equals(existing.path("source").asText())) return existing;
        JsonNode result;
        try {
            result=source.detail(existing.path("site").asText(),existing.path("externalId").asText(),existing.path("nature").asText());
            if(!result.isObject() || result.path("description").asText().isBlank()
                    && result.path("requirement").asText().isBlank()) throw new BusinessException(ErrorCode.TOOL_FAILED);
        } catch(BusinessException ex) {
            if(!source.fallbackToStored() || ex.errorCode()!=ErrorCode.TOOL_FAILED) throw ex;
            var fallback=(ObjectNode)existing.deepCopy();
            fallback.put("originalSource",existing.path("source").asText());
            fallback.put("source","STORED_SNAPSHOT").put("fallbackReason","LIVE_DETAIL_UNAVAILABLE");
            return fallback;
        }
        var raw=(ObjectNode)result.deepCopy();
        raw.put("id",existing.path("externalId").asText()); raw.put("site",existing.path("site").asText());
        var data=normalize(raw,existing.path("nature").asText());
        for(String field:List.of("title","company","locations","category","department","url","updatedAt"))
            if(data.path(field).asText().isBlank()) data.set(field,existing.path(field));
        jdbc.update("UPDATE jobs SET payload=?::jsonb,detail_fetched_at=now(),updated_at=now() WHERE id=?",data.toString(),id);
        return get(id);
    }
    private ObjectNode normalize(JsonNode raw,String nature) {
        var data=json.createObjectNode();
        for(String field:List.of("site","company","title","locations","category","department","updatedAt","description","requirement")) {
            String value=raw.path(field).asText("").replaceAll("(?is)<[^>]*>"," ").replace("&nbsp;"," ").replace("&amp;","&").strip();
            if(value.length()>30000) value=com.offerpilot.common.text.TextBounds.truncate(value,30000);
            data.put(field,value);
        }
        data.put("externalId",raw.path("id").asText()); data.put("nature",nature);
        String url=raw.path("url").asText("");
        try { var uri=java.net.URI.create(url); if(!Set.of("https","http").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null) url=""; }
        catch(Exception ex) { url=""; }
        data.put("url",url);
        boolean local = "LOCAL_SNAPSHOT".equals(raw.path("source").asText());
        data.put("source",local ? "LOCAL_SNAPSHOT" : "company-career-site");
        if(local) data.put("snapshotDate",raw.path("snapshotDate").asText());
        if(raw.path("skills").isArray()) data.set("skills",raw.path("skills").deepCopy());
        return data;
    }
    private JsonNode view(String id,String payload,java.sql.Timestamp fetched) {
        try { var node=(ObjectNode)json.readTree(payload); node.put("id",id); if(fetched==null) node.putNull("detailFetchedAt"); else node.put("detailFetchedAt",fetched.toInstant().toString()); return node; }
        catch(Exception ex) { throw new IllegalStateException(ex); }
    }
    public static String evidenceText(JsonNode job) {
        return "岗位："+job.path("title").asText()+"\n职责：\n"+job.path("description").asText()+"\n要求：\n"+job.path("requirement").asText();
    }
    @Override
    public String description(String jobId) {
        var job = get(jobId);
        if (job.path("description").asText().isBlank() && job.path("requirement").asText().isBlank())
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "岗位缺少职责与要求，请先获取完整详情或在简历改写页粘贴JD");
        String jd = evidenceText(job);
        if (jd.length() > 20000)
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "岗位内容过长，请在简历改写页核对并精简JD");
        return jd;
    }
}
