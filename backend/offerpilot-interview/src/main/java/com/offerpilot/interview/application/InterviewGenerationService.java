package com.offerpilot.interview.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.common.artifact.BackgroundWork;
import com.offerpilot.common.error.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;

/** Persist model output only when its owned input snapshot is still current. */
@Service
public class InterviewGenerationService {
    private final InterviewService interviews;
    private final InterviewIntelligence model;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    public InterviewGenerationService(InterviewService interviews,InterviewIntelligence model,JdbcTemplate jdbc,ObjectMapper json,TransactionTemplate tx) {
        this.interviews=interviews; this.model=model; this.jdbc=jdbc; this.json=json; this.tx=tx;
    }
    private record Snapshot(InterviewService.Session session,long revision,String resume,String jd) {}
    private Snapshot snapshot(String owner,String id) {
        return tx.execute(status -> {
        var session=interviews.getForUpdate(owner,id);
        var row=jdbc.queryForMap("SELECT revision,resume_context,job_context FROM interview_sessions WHERE id=? AND user_id=?",id,owner);
        if (session.generationSource()==null) throw new BusinessException(ErrorCode.VALIDATION_FAILED,"旧会话继续使用原规则报告，请新建面试使用模型生成。");
        return new Snapshot(session,((Number)row.get("revision")).longValue(),Objects.toString(row.get("resume_context"),""),Objects.toString(row.get("job_context"),""));
        });
    }
    private void check(String owner,Snapshot snapshot) {
        BackgroundWork.check();
        var row=jdbc.queryForList("SELECT revision FROM interview_sessions WHERE id=? AND user_id=? FOR UPDATE",Long.class,snapshot.session().id(),owner);
        if (row.isEmpty()) throw new BusinessException(ErrorCode.INTERVIEW_NOT_FOUND);
        if (row.get(0)!=snapshot.revision()) throw new BusinessException(ErrorCode.VALIDATION_FAILED,"回答已改变，请基于最新会话重新生成。");
        BackgroundWork.check();
    }
    public InterviewService.Session questions(String owner,String id) {
        var snapshot=snapshot(owner,id); var session=snapshot.session();
        if (session.completed() || session.answers().stream().anyMatch(a -> !a.fullText().isBlank()))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,"已有回答的会话不能替换题目，请新建面试。");
        if ("model".equals(session.generationSource())) return session;
        List<InterviewIntelligence.Question> questions;
        try { questions=model.questions(owner,session,snapshot.resume(),snapshot.jd()); }
        catch (RuntimeException ex) {
            BackgroundWork.check(); questions=new ArrayList<>();
            var texts=session.options().fallbackQuestions(session.role());
            for (int i=0;i<texts.size();i++) questions.add(new InterviewIntelligence.Question(texts.get(i),session.options().questionTypes().get(i),session.options().difficulty(),"rule"));
        }
        var generated=List.copyOf(questions);
        tx.executeWithoutResult(status -> {
            check(owner,snapshot);
            for (int i=0;i<generated.size();i++) {
                var q=generated.get(i);
                jdbc.update("UPDATE interview_answers SET question_text=?,question_type=?,difficulty=?,question_source=?,evaluation=NULL WHERE session_id=? AND question_index=?",q.text(),q.type(),q.difficulty(),q.source(),id,i);
            }
            jdbc.update("UPDATE interview_sessions SET generation_source=?,revision=revision+1,report=NULL,updated_at=now() WHERE id=? AND user_id=?",generated.get(0).source(),id,owner);
        });
        return interviews.get(owner,id);
    }
    public InterviewService.Session score(String owner,String id,int index) {
        var snapshot=snapshot(owner,id); var session=snapshot.session();
        if (index<0 || index>=session.answers().size()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        var answer=session.answers().get(index);
        if (answer.evaluation()!=null && "model".equals(answer.evaluation().path("source").asText())) return session;
        var saved=evaluate(owner,snapshot,answer);
        tx.executeWithoutResult(status -> {
            check(owner,snapshot);
            jdbc.update("UPDATE interview_answers SET evaluation=?::jsonb WHERE session_id=? AND question_index=?",saved.toString(),id,index);
            jdbc.update("UPDATE interview_sessions SET report=NULL,revision=revision+1,updated_at=now() WHERE id=? AND user_id=?",id,owner);
        });
        return interviews.get(owner,id);
    }
    public InterviewRubric.Report report(String owner,String id) {
        var initial=snapshot(owner,id);
        if (!initial.session().completed()) throw new BusinessException(ErrorCode.VALIDATION_FAILED,"请先结束面试。");
        scoreMissing(owner,initial);
        var snapshot=snapshot(owner,id); var session=snapshot.session();
        if (!session.completed()) throw new BusinessException(ErrorCode.VALIDATION_FAILED,"会话已重新开始，请结束后再生成报告。");
        var report=aggregate(session);
        var input=json.createObjectNode().put("role",session.role()); input.set("report",json.valueToTree(report));
        InterviewRubric.Report result=report;
        if (report.overall()!=null) try {
            var narrative=model.narrative(owner,session,input);
            var type=new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){};
            List<String> strengths=json.convertValue(narrative.path("strengths"),type), weaknesses=json.convertValue(narrative.path("weaknesses"),type), advice=json.convertValue(narrative.path("advice"),type);
            result=new InterviewRubric.Report(id,report.overall(),report.dimensions(),report.turns(),strengths,weaknesses,advice,learning(weaknesses),report.note()+"；模型总结");
        } catch (RuntimeException ex) { BackgroundWork.check(); }
        var saved=result;
        tx.executeWithoutResult(status -> {
            check(owner,snapshot);
            try { jdbc.update("UPDATE interview_sessions SET report=?::jsonb,updated_at=now() WHERE id=? AND user_id=?",json.writeValueAsString(saved),id,owner); }
            catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalStateException(ex); }
        });
        return saved;
    }
    private ObjectNode evaluate(String owner,Snapshot snapshot,InterviewService.Answer answer) {
        BackgroundWork.check();
        if (answer.fullText().isBlank()) return model.unavailable("本题已跳过；没有回答证据，不评分。");
        try { return model.score(owner,snapshot.session(),answer,snapshot.resume(),snapshot.jd()); }
        catch (RuntimeException ex) {
            BackgroundWork.check();
            return model.unavailable("模型不可用或输出未通过校验，本题未评分；可稍后重试。规则提示仅供练习，不代替语义评分。");
        }
    }
    private void scoreMissing(String owner,Snapshot snapshot) {
        var missing=snapshot.session().answers().stream().filter(a -> a.evaluation()==null).toList();
        if (missing.isEmpty()) return;
        var parent=Thread.currentThread();
        var executor=Executors.newFixedThreadPool(Math.min(3,missing.size()));
        var futures=new ArrayList<Future<ObjectNode>>(); var results=new ArrayList<ObjectNode>();
        try {
            for (var answer:missing) {
                BackgroundWork.check();
                futures.add(executor.submit(() -> BackgroundWork.run(() -> !parent.isInterrupted(),
                        () -> evaluate(owner,snapshot,answer))));
            }
            for (var future:futures) { results.add(future.get()); BackgroundWork.check(); }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.GENERATION_CANCELLED);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(ex.getCause());
        } finally {
            for (var future:futures) if (!future.isDone()) future.cancel(true);
            executor.shutdownNow();
        }
        // Concurrent calls only compute. One revision check guards all writes; siblings cannot invalidate each other.
        tx.executeWithoutResult(status -> {
            check(owner,snapshot);
            for (int i=0;i<missing.size();i++)
                jdbc.update("UPDATE interview_answers SET evaluation=?::jsonb WHERE session_id=? AND question_index=?",
                        results.get(i).toString(),snapshot.session().id(),missing.get(i).questionIndex());
            jdbc.update("UPDATE interview_sessions SET report=NULL,revision=revision+1,updated_at=now() WHERE id=? AND user_id=?",
                    snapshot.session().id(),owner);
        });
    }
    public static InterviewRubric.Report aggregate(InterviewService.Session session) {
        var turns=new ArrayList<InterviewRubric.Turn>(); var strengths=new LinkedHashSet<String>(); var weaknesses=new LinkedHashSet<String>();
        int[] totals=new int[4]; int scored=0;
        for (var answer:session.answers()) {
            var eval=answer.evaluation(); boolean rated=eval!=null && "model".equals(eval.path("source").asText());
            InterviewRubric.Dimensions dimensions=null; Integer score=null;
            if (rated) {
                int[] values=new int[4];
                for(int i=0;i<4;i++) { values[i]=eval.path("dimensions").path(InterviewIntelligence.DIMENSIONS.get(i)).path("score").asInt(); totals[i]+=values[i]; }
                dimensions=new InterviewRubric.Dimensions(values[0],values[1],values[2],values[3]); score=eval.path("score").asInt(); scored++;
                for(var item:eval.path("strengths")) strengths.add("第"+(answer.questionIndex()+1)+"题："+item.asText());
                for(var item:eval.path("weaknesses")) weaknesses.add("第"+(answer.questionIndex()+1)+"题："+item.asText());
            }
            turns.add(new InterviewRubric.Turn(answer.questionText(),answer.fullText(),score,InterviewRubric.detect(answer.fullText()),eval==null?"尚未评分":eval.path("rationale").asText(),List.of(),dimensions,eval==null?null:eval.path("improvedAnswer").asText(),rated?"model":"unavailable",eval));
        }
        var dims=scored==0?new InterviewRubric.Dimensions(null,null,null,null):new InterviewRubric.Dimensions(totals[0]/scored,totals[1]/scored,totals[2]/scored,totals[3]/scored);
        Integer overall=scored==0?null:(totals[0]+totals[1]+totals[2]+totals[3])/(scored*4);
        var advice=weaknesses.stream().limit(8).map(w -> "围绕「"+w+"」补充真实依据，重答并比较反馈。").toList();
        return new InterviewRubric.Report(session.id(),overall,dims,List.copyOf(turns),List.copyOf(strengths),List.copyOf(weaknesses),advice,learning(List.copyOf(weaknesses)),"模型评分0–100；已评分 "+scored+"/"+turns.size()+" 题，仅按已评分题汇总；未作答或模型不可用不计分。总结失败时保留逐题汇总。仅供练习，不预测录用。");
    }
    private static List<InterviewRubric.Learning> learning(List<String> weaknesses) {
        return weaknesses.stream().limit(8).map(w -> new InterviewRubric.Learning("面试表达与专业练习",w,1)).toList();
    }
}
