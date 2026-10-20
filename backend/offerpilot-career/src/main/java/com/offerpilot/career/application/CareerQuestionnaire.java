package com.offerpilot.career.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Original Chinese activity prompts using the RIASEC framework; not a licensed/validated O*NET instrument. */
@Service
public class CareerQuestionnaire {
    public record Question(String id, String axis, String text) {}
    public record State(List<Question> questions, Map<String,Integer> answers, int revision, boolean completed,
                        Map<String,Integer> scores, String code) {}
    public static final List<Question> QUESTIONS;
    static {
        String[][] prompts = {
            {"把松动的书架修到稳固可用", "照着示意图组装一个小装置", "在户外观察并照料植物", "用工具把材料做成实物", "排查家中设备无法工作的原因"},
            {"设计小实验检验自己的猜想", "比较几组数据寻找规律", "追查一个现象背后的原因", "推演一道没有现成答案的难题", "阅读资料弄懂新技术的原理"},
            {"为一段故事画出不同的场景", "用声音或旋律表达一种情绪", "把普通物品重新设计得更有趣", "写一篇带有个人风格的短文", "剪辑一段能传达自己想法的视频"},
            {"耐心教别人学会一项新技能", "倾听朋友的困惑并陪他梳理", "组织一次帮助社区成员的活动", "为新同学介绍如何适应校园", "把复杂知识讲到别人听懂"},
            {"说服伙伴支持一项新提议", "带领小组把活动从想法变成现实", "向潜在使用者介绍一个产品", "协调不同意见争取共同方案", "寻找机会发起一个小项目"},
            {"把混乱的资料按规则归档", "核对表格找出遗漏和错误", "制定清楚的步骤让工作有序进行", "持续记录并整理一项活动的收支", "按约定标准检查一批成果"}
        };
        var questions = new ArrayList<Question>();
        for (int i=0;i<6;i++) for (int j=0;j<5;j++) {
            String axis = String.valueOf("RIASEC".charAt(i));
            questions.add(new Question(axis+(j+1),axis,prompts[i][j]));
        }
        QUESTIONS=List.copyOf(questions);
    }
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CareerAssessmentService assessments;
    public CareerQuestionnaire(JdbcTemplate jdbc, ObjectMapper json, CareerAssessmentService assessments) {
        this.jdbc=jdbc; this.json=json; this.assessments=assessments;
    }
    @Transactional(readOnly=true)
    public State get(String owner) {
        var rows=jdbc.query("SELECT * FROM career_questionnaires WHERE user_id=?",(rs,n) -> {
            try {
                Map<String,Integer> answers=json.readValue(rs.getString("answers"),new TypeReference<>() {});
                int revision=rs.getInt("revision");
                boolean complete=Objects.equals(rs.getObject("completed_revision",Integer.class),revision);
                var scores=answers.size()==30 ? score(answers) : Map.<String,Integer>of();
                var confirmed=complete ? assessments.confirmed(owner) : null;
                complete=complete && confirmed!=null && confirmed.riasec().equals(scores);
                return new State(QUESTIONS,answers,revision,complete,scores,scores.isEmpty()||scores.values().stream().distinct().count()==1 ? "" : CareerAssessmentService.code(scores));
            } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
        },owner);
        return rows.isEmpty()?new State(QUESTIONS,Map.of(),0,false,Map.of(),""):rows.get(0);
    }
    @Transactional
    public State answer(String owner,String id,Integer value) {
        if (QUESTIONS.stream().noneMatch(q -> q.id().equals(id)) || value==null || value<1 || value>5)
            throw invalid("题目不存在或答案不在1–5之间");
        lock(owner);
        var current=get(owner);
        if (Objects.equals(current.answers().get(id),value)) return current;
        var answers=new LinkedHashMap<>(current.answers()); answers.put(id,value);
        try { jdbc.update("UPDATE career_questionnaires SET answers=?::jsonb,revision=revision+1,updated_at=now() WHERE user_id=?",json.writeValueAsString(answers),owner); }
        catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
        return get(owner);
    }
    @Transactional
    public State complete(String owner,int revision) {
        lock(owner);
        var current=get(owner);
        if (current.revision()!=revision) throw invalid("答案已改变，请刷新后重新确认");
        if (current.completed()) return current;
        if (current.answers().size()!=30) throw invalid("请完成全部30题");
        // Explicit submit replaces only six interest dimensions; confirmed traits and constraints survive.
        assessments.confirmQuestionnaire(owner,current.scores());
        jdbc.update("UPDATE career_questionnaires SET completed_revision=revision WHERE user_id=?",owner);
        return get(owner);
    }
    public static Map<String,Integer> score(Map<String,Integer> answers) {
        var scores=new LinkedHashMap<String,Integer>();
        for (char axis : "RIASEC".toCharArray()) {
            int sum=0;
            for (int i=1;i<=5;i++) {
                Integer value=answers.get(axis+String.valueOf(i));
                if (value==null||value<1||value>5) throw invalid("请完成全部30题");
                sum+=value;
            }
            scores.put(String.valueOf(axis),(sum-5)*5); // 5..25 -> 0..100
        }
        return Collections.unmodifiableMap(scores);
    }
    private void lock(String owner) {
        if (jdbc.queryForList("SELECT id FROM users WHERE id=? AND status='ACTIVE' FOR UPDATE",String.class,owner).isEmpty())
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        jdbc.update("INSERT INTO career_questionnaires(user_id) VALUES (?) ON CONFLICT DO NOTHING",owner);
        jdbc.queryForObject("SELECT user_id FROM career_questionnaires WHERE user_id=? FOR UPDATE",String.class,owner);
    }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.VALIDATION_FAILED,message); }
}
