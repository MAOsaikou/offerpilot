package com.offerpilot.career.application;

import com.fasterxml.jackson.databind.*;
import com.offerpilot.common.error.*;
import com.offerpilot.common.notification.NotificationSink;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Service
public class CareerTargetPlans {
    public record Plan(String revision,String target,String city,String status,JsonNode result,String notice) {}
    private final JdbcTemplate jdbc; private final ObjectMapper json; private final TargetJobSearch jobs;
    private final SkillGapService gaps; private final CoachPlanForChat coach;
    private final NotificationSink notices; private final TransactionTemplate tx;
    public CareerTargetPlans(JdbcTemplate jdbc,ObjectMapper json,TargetJobSearch jobs,SkillGapService gaps,
                             CoachPlanForChat coach,NotificationSink notices,TransactionTemplate tx) {
        this.jdbc=jdbc;this.json=json;this.jobs=jobs;this.gaps=gaps;this.coach=coach;this.notices=notices;this.tx=tx;
    }
    public Plan get(String owner) {
        var rows=jdbc.query("SELECT * FROM career_target_plans WHERE user_id=?",(rs,n)->new Plan(rs.getString("revision"),
            rs.getString("target"),rs.getString("city"),rs.getString("status"),parse(rs.getString("result")),rs.getString("notice")),owner);
        return rows.isEmpty()?null:rows.get(0);
    }
    public Plan confirm(String owner,String revision) {
        if(jdbc.update("UPDATE career_target_plans SET status='CONFIRMED',updated_at=now() WHERE user_id=? AND revision=? AND status IN ('SUGGESTED','CONFIRMED')",owner,revision)==0)
            throw new BusinessException(ErrorCode.RESUME_VERSION_CONFLICT,"目标已变化或建议尚未就绪，请重新读取");
        return get(owner);
    }
    public Plan retry(String owner,String revision) {
        if(jdbc.update("UPDATE career_target_plans SET status='PENDING',updated_at=now() WHERE user_id=? AND revision=? AND status='FAILED'",owner,revision)==0)
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,"仅可重试当前失败的联动");
        return get(owner);
    }
    @Scheduled(initialDelayString="${offerpilot.career.target-initial-delay-ms:15000}",fixedDelayString="${offerpilot.career.target-delay-ms:60000}")
    public void scheduled() {
        var owners=jdbc.queryForList("SELECT user_id FROM career_target_plans WHERE status='PENDING' OR (status='RUNNING' AND updated_at<now()-interval '10 minutes') ORDER BY updated_at LIMIT 5",String.class);
        for(String owner:owners) try { generate(owner); }
        catch(RuntimeException ex) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Target follow-up deferred: {}",ex.getClass().getSimpleName()); }
    }
    public void generate(String owner) {
        Plan draft=get(owner); if(draft==null) return;
        int claimed=jdbc.update("""
            UPDATE career_target_plans SET status='RUNNING',updated_at=now() WHERE user_id=? AND revision=?
            AND (status='PENDING' OR (status='RUNNING' AND updated_at<now()-interval '10 minutes'))
            """,owner,draft.revision());
        if(claimed==0) return;
        var result=json.createObjectNode(); var messages=new ArrayList<String>(); boolean failed=false;
        try {
            var found=jobs.search(owner,draft.target(),draft.city());result.set("jobs",found);
            if(found.path("meta").path("partial").asBoolean()) {
                messages.add("岗位搜索仅返回部分结果，可重试。");
                // 有可用岗位时仅提示降级，空结果且来源失败仍按失败处理。
                if(found.path("items").isEmpty()) failed=true;
            }
        }
        catch(RuntimeException ex) { failed=true;messages.add("岗位搜索未完成，可重试或到岗位页搜索。"); }
        try { result.set("skillGap",json.valueToTree(gaps.analyze(owner,null,null,null,null))); }
        catch(BusinessException ex) { failed=true;messages.add("技能差距未生成，请在成长页选择目录职业后重试。"); }
        try { result.set("learning",json.valueToTree(coach.plan(owner,null,null,draft.revision()))); }
        catch(BusinessException ex) { failed=true;messages.add("学习建议未生成，可到成长教练继续。"); }
        String notice=String.join(" ",messages); String planStatus=failed?"FAILED":"SUGGESTED";
        tx.executeWithoutResult(status->{
            if(jdbc.queryForList("SELECT id FROM users WHERE id=? AND status='ACTIVE' FOR UPDATE",String.class,owner).isEmpty()) return;
            int saved=jdbc.update("UPDATE career_target_plans SET status=?,result=?::jsonb,notice=?,updated_at=now() WHERE user_id=? AND revision=? AND status='RUNNING'",
                planStatus,result.toString(),notice,owner,draft.revision());
            if(saved==1) notices.send(owner,"CAREER_TARGET","target:"+draft.revision(),"目标职业的下一步已准备好",
                "已按「"+draft.target()+"」尝试搜索岗位并分析技能与学习路线，请查看并确认。"+notice,"/career");
        });
    }
    private JsonNode parse(String raw) {
        try { return raw==null?null:json.readTree(raw); } catch(Exception ex) { throw new IllegalStateException("Invalid target plan",ex); }
    }
}
