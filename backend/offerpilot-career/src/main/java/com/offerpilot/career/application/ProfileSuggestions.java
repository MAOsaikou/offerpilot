package com.offerpilot.career.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.*;
import com.offerpilot.common.id.IdGenerator;
import com.offerpilot.common.notification.NotificationSink;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Service
public class ProfileSuggestions {
    public record Suggestion(String id,String sourceType,String sourceId,List<String> skills,List<String> facts,String status,String createdAt) {}
    private final JdbcTemplate jdbc; private final ObjectMapper json; private final ProfileEvidenceSource source;
    private final CareerProfileService profiles; private final NotificationSink notices; private final TransactionTemplate tx;
    private String cursor="";
    public ProfileSuggestions(JdbcTemplate jdbc,ObjectMapper json,ProfileEvidenceSource source,CareerProfileService profiles,NotificationSink notices,TransactionTemplate tx) {
        this.jdbc=jdbc;this.json=json;this.source=source;this.profiles=profiles;this.notices=notices;this.tx=tx;
    }
    @Scheduled(initialDelayString="${offerpilot.career.profile-initial-delay-ms:30000}",fixedDelayString="${offerpilot.career.profile-delay-ms:60000}")
    public void scheduled() {
        var owners=source.owners(cursor,25);
        for(String owner:owners) {
            try { refresh(owner); }
            catch(RuntimeException ex) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Profile reconciliation deferred: {}",ex.getClass().getSimpleName()); }
            cursor=owner;
        }
        if(owners.size()<25) cursor="";
    }
    public List<Suggestion> list(String owner) {
        refresh(owner);
        return jdbc.query("SELECT * FROM career_profile_suggestions WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 50",
            (rs,n)->new Suggestion(rs.getString("id"),rs.getString("source_type"),rs.getString("source_id"),strings(rs.getString("skills")),
                strings(rs.getString("facts")),rs.getString("status"),rs.getTimestamp("created_at").toInstant().toString()),owner);
    }
    public void refresh(String owner) {
        var observations=source.read(owner);
        tx.executeWithoutResult(status->{
            if(!lock(owner)) return;
            for(var observation:observations) {
                if(observation.skills().isEmpty() && observation.facts().isEmpty()) continue;
                String fingerprint=fingerprint(observation), id=IdGenerator.next("psg");
                int inserted=jdbc.update("""
                    INSERT INTO career_profile_suggestions(id,user_id,source_type,source_id,fingerprint,skills,facts)
                    VALUES (?,?,?,?,?,?::jsonb,?::jsonb) ON CONFLICT(user_id,source_type,source_id,fingerprint) DO NOTHING
                    """,id,owner,observation.type(),observation.sourceId(),fingerprint,encode(observation.skills()),encode(observation.facts()));
                if(inserted==1) notices.send(owner,"PROFILE_UPDATE","profile:"+id,"职业画像有新的材料建议",
                    "来自 "+observation.type()+" 的材料已整理，请核对后确认；当前画像尚未改动。","/discover#profile-updates");
            }
            // Pending deleted/changed sources must not remain actionable forever.
            var valid=observations.stream().map(ProfileSuggestions::fingerprint).toList();
            jdbc.query("SELECT id,fingerprint FROM career_profile_suggestions WHERE user_id=? AND status='PENDING'",
                (org.springframework.jdbc.core.RowCallbackHandler)rs->{
                    if(!valid.contains(rs.getString("fingerprint"))) jdbc.update("UPDATE career_profile_suggestions SET status='STALE' WHERE id=? AND user_id=?",rs.getString("id"),owner);
                },owner);
        });
    }
    public CareerProfileService.Profile decide(String owner,String id,boolean accept,List<String> skills) {
        return tx.execute(status->{
            if(!lock(owner)) throw new BusinessException(ErrorCode.UNAUTHENTICATED);
            var rows=jdbc.queryForList("SELECT source_type,source_id,fingerprint,skills::text,status FROM career_profile_suggestions WHERE user_id=? AND id=? FOR UPDATE",owner,id);
            if(rows.isEmpty()) throw new BusinessException(ErrorCode.ROUTE_NOT_FOUND);
            var row=rows.get(0); String current=(String)row.get("status");
            if((accept && current.equals("CONFIRMED")) || (!accept && current.equals("REJECTED"))) return profiles.get(owner);
            if(!current.equals("PENDING")) throw new BusinessException(ErrorCode.RESUME_VERSION_CONFLICT,"建议已处理或来源已变化，请重新读取");
            if(accept) {
                var available=source.read(owner).stream().anyMatch(o->fingerprint(o).equals(row.get("fingerprint")));
                if(!available) throw new BusinessException(ErrorCode.RESUME_VERSION_CONFLICT,"来源已变化，请重新读取建议");
                var proposed=strings((String)row.get("skills"));
                if(skills==null || !proposed.containsAll(skills)) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
                var profile=profiles.get(owner);
                String merged=merge(profile.skills(),skills);
                profiles.save(owner,profile.target(),profile.city(),merged,profile.interests());
            }
            jdbc.update("UPDATE career_profile_suggestions SET status=?,decided_at=now() WHERE id=? AND user_id=?",accept?"CONFIRMED":"REJECTED",id,owner);
            return profiles.get(owner);
        });
    }
    static String merge(String existing,List<String> additions) {
        var seen=new HashSet<String>(); Arrays.stream(existing.split("[、,，;；\\n]+" )).map(String::strip).map(s->s.toLowerCase(Locale.ROOT)).forEach(seen::add);
        StringBuilder result=new StringBuilder(existing);
        for(String skill:additions) if(seen.add(skill.toLowerCase(Locale.ROOT))) { if(!result.isEmpty()) result.append("\n"); result.append(skill); }
        if(result.codePointCount(0,result.length())>3000) throw new BusinessException(ErrorCode.VALIDATION_FAILED,"合并后技能超过3000字，请先整理画像或减少采纳项");
        return result.toString();
    }
    static String fingerprint(ProfileEvidenceSource.Observation o) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(new ObjectMapper().writeValueAsBytes(o))); }
        catch(java.security.NoSuchAlgorithmException | com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }
    private boolean lock(String owner) { return !jdbc.queryForList("SELECT id FROM users WHERE id=? AND status='ACTIVE' FOR UPDATE",String.class,owner).isEmpty(); }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch(Exception ex) { throw new IllegalStateException(ex); } }
    private List<String> strings(String raw) { try { return json.readValue(raw,new TypeReference<List<String>>(){}); } catch(Exception ex) { throw new IllegalStateException(ex); } }
}
