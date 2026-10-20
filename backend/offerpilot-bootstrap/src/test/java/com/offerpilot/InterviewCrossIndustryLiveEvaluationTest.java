package com.offerpilot;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.offerpilot.config.OfferPilotProperties;
import com.offerpilot.interview.application.*;
import com.offerpilot.llm.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.retry.policy.NeverRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import reactor.core.publisher.Flux;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;

/** Paid opt-in: real Intelligence/prompts/Gateway/aggregate/narrative, no business DB or HTTP.
 * Fictional answers and keyword flags require human semantic review. */
@Tag("eval")
@EnabledIfEnvironmentVariable(named="OFFERPILOT_RUN_LIVE_INTERVIEW", matches="true")
@EnabledIfEnvironmentVariable(named="DEEPSEEK_API_KEY", matches=".*\\S.*")
class InterviewCrossIndustryLiveEvaluationTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static final Pattern TECH=Pattern.compile("代码|算法|系统设计|数据库|编程|微服务|分布式|Java|Python|SQL|LeetCode",Pattern.CASE_INSENSITIVE);
    static final Pattern LEGACY_TECH=Pattern.compile("关键技术|系统的性能|线上故障|技术方案|测试关键边界");
    record Scenario(String role,String industry,String focus,String difficulty,int count,Map<String,Integer> mix,
                    String jd,String keywords,String good,String bad) {
        InterviewOptions options() {return new InterviewOptions(null,null,difficulty,"TEXT",30,count,mix,industry,"END");}
        String answer(String type,boolean strong) {
            if(!strong) return bad;
            // Past-tense evidence for behavioral; explicit proposed actions for other question types.
            return type.equals("BEHAVIORAL") ? "在虚构的见习练习场景中，我负责相关工作。"+good+"结果完成了核验，并记录待改进事项。"
                : "我会按以下步骤处理，并说明风险和验证方法："+good;
        }
    }
    static List<Scenario> scenarios() {
        return List.of(
            new Scenario("新媒体运营","内容营销","行为","EASY",3,Map.of("BEHAVIORAL",1,"SITUATIONAL",1,"KNOWLEDGE",1),
                "校园公益账号选题、内容发布、用户反馈及效果复盘，注意版权隐私。","内容|选题|用户|账号|传播|粉丝|运营|平台|版权|互动",
                "我先确认受众和报名目标，访谈同学并核对素材授权，与同伴复核文案。按曝光、阅读、报名排查，区分选题和入口问题，小范围修改后比较同口径数据，不把曝光当转化。",
                "我只看点赞，直接复制热门账号，不核对授权。效果不好就多发，不复盘。"),
            new Scenario("护士","医疗护理","技术","STANDARD",4,Map.of("KNOWLEDGE",2,"SITUATIONAL",1,"CASE",1),
                "病区基础护理、患者身份核对、病情观察、宣教与规范上报；不要求诊断或独立处方。","患者|护理|护士|病情|病区|医嘱|核对|用药|宣教|感染|交接|安全",
                "我先确认患者即时安全，核对身份和医嘱，按岗位规范及时报告带教或主管护士及医生，不擅自诊断或调整处方。用图示宣教并请患者复述，记录观察和处理，交接待处理风险，请带教复核。",
                "我凭印象操作，不用核对身份；病情变了先等等，怕麻烦就不报告。"),
            new Scenario("销售","企业服务","综合","HARD",5,Map.of(),
                "企业客户需求访谈、预算确认、异议处理、报价边界、回款与复购，不夸大产品能力。","客户|销售|成交|需求|报价|预算|回款|合同|产品|采购|异议|决策",
                "我先确认客户使用场景、预算、决策人和采购流程，与产品同事核实交付边界。澄清异议后提供授权范围内的报价和试用方案，不承诺未经确认的功能，约定验收、回访和回款条件，记录待办与风险。",
                "客户嫌贵就随便降价，先承诺所有功能，签单后交付和回款不用我管。"),
            new Scenario("财务","企业财务","综合","HARD",6,Map.of("BEHAVIORAL",1,"SITUATIONAL",1,"KNOWLEDGE",2,"CASE",2),
                "报销审核、凭证核验、账实核对、月结差异追踪和内控，遵守审批与职责分离。","财务|凭证|报销|账|费用|审批|资金|发票|月结|内控|核算|税",
                "我先核对原始凭证、金额、业务用途和审批权限，缺材料退回补正。对照明细账与对账资料区分时间差异、录入错误和异常业务，向负责人报告，按授权更正并保留审计轨迹，不能为结账直接调平，经办审批付款职责分离。",
                "有领导口头同意就直接付款，凭证以后补；对不上就改数字让账平掉。"),
            new Scenario("人力资源专员","人力资源","HR","STANDARD",7,Map.of(),
                "招聘需求澄清、结构化面试、候选人沟通、入职材料及个人信息保护，避免歧视。","招聘|候选|面试|入职|员工|岗位|人力|隐私|用人|劳动|录用|信息|歧视",
                "我先与用人经理核对必需能力和评价标准，用一致问题及行为证据评价，及时告知候选人流程变化。查看环节流转记录和反馈，区分渠道不匹配与流程拖延；最小必要收集资料，限制授权访问，不因无关个人特征判断。",
                "我凭第一印象筛人，把候选人材料发到大群，入职和招聘标准随意处理。"),
            new Scenario("幼儿园教师","学前教育","综合","EASY",8,Map.of("BEHAVIORAL",2,"SITUATIONAL",2,"KNOWLEDGE",2,"CASE",2),
                "幼儿入园适应、游戏活动、安全看护、观察记录与家园沟通，尊重发展差异，禁止体罚。","幼儿|儿童|家长|游戏|活动|教师|孩子|安全|入园|发展|观察|教育",
                "我先保证幼儿安全，检查场地材料，平静接纳情绪，记录场景和可观察行为，为不愿加入的孩子提供安静选择，用适龄游戏和简短规则邀请参与，向带班教师反馈并与家长沟通，持续观察，不凭一次表现贴标签，不训斥体罚。",
                "孩子不听话就罚站，让所有孩子按同样速度完成活动，不观察也不与家长沟通。")
        );
    }
    @Test void evaluateAllIndustries() throws Exception {
        String configured=System.getProperty("offerpilot.interview.output");
        assertThat(configured).as("Explicit external output directory required").isNotBlank();
        Path directory=Path.of(configured).toAbsolutePath().normalize();
        Path repo=Path.of("").toAbsolutePath();
        while(repo!=null && !Files.exists(repo.resolve(".git"))) repo=repo.getParent();
        assertThat(repo).as("Repository root must be found before writing raw output").isNotNull();
        Files.createDirectories(directory);
        assertThat(directory.toRealPath().startsWith(repo.toRealPath())).as("Raw outputs must stay outside repository").isFalse();
        String phase=System.getProperty("offerpilot.interview.phase","baseline");assertThat(phase).matches("[a-z0-9-]+");
        Path run=Files.createDirectory(directory.resolve(phase+"-"+UUID.randomUUID()));
        var gateway=new RecordingGateway(run);var beans=new StaticListableBeanFactory();beans.addBean("gateway",gateway);
        var model=new InterviewIntelligence(beans.getBeanProvider(ModelGateway.class),JSON,new ClassPathResource("prompts/interview/questions.st"),
            new ClassPathResource("prompts/interview/score.st"),new ClassPathResource("prompts/interview/report.st"));
        var results=JSON.createArrayNode();
        for(var c:scenarios()) {
            c.options().validate();
            var row=results.addObject().put("role",c.role()).put("phase",phase).put("requestedCount",c.count())
                .put("semanticReviewRequired",true).put("status","NOT_MEASURED");
            row.set("options",JSON.valueToTree(c.options()));row.put("jobDescription",c.jd());
            var rules=c.options().fallbackQuestions(c.role());row.set("fallbackQuestions",JSON.valueToTree(rules));
            row.put("fallbackUniqueCount",new HashSet<>(rules).size()).put("fallbackTechFlags",rules.stream().filter(q -> TECH.matcher(q).find()).count());
            var legacy=c.options().questions(c.role(),c.focus(),null);row.set("legacyQuestions",JSON.valueToTree(legacy));
            row.put("legacyTechFlags",legacy.stream().filter(q -> TECH.matcher(q).find() || LEGACY_TECH.matcher(q).find()).count());
            var max=new InterviewOptions(null,null,c.difficulty(),"TEXT",60,15,Map.of(),c.industry(),"END").fallbackQuestions(c.role());
            row.put("fallback15UniqueCount",new HashSet<>(max).size());
            long start=System.nanoTime();int calls=gateway.calls.get();
            try {
                var initial=session(c,List.of(),false);var questions=model.questions("live-fictional",initial,"",c.jd());
                row.put("questionMs",elapsed(start)).put("actualCount",questions.size()).put("countMatch",questions.size()==c.count());
                row.set("questions",JSON.valueToTree(questions));var types=new TreeMap<String,Integer>();int relevant=0,leaks=0;
                for(var q:questions) {types.merge(q.type(),1,Integer::sum);if(Pattern.compile(c.keywords()).matcher(q.text()).find()) relevant++;if(TECH.matcher(q.text()).find()) leaks++;}
                row.set("typeDistribution",JSON.valueToTree(types));row.put("keywordRelevantCount",relevant).put("techFlagCount",leaks);
                row.put("typeMixMatch",c.mix().isEmpty() || c.mix().entrySet().stream().allMatch(e -> types.getOrDefault(e.getKey(),0).equals(e.getValue())));
                for(boolean strong:List.of(true,false)) {
                    String label=strong?"good":"bad";var answers=new ArrayList<InterviewService.Answer>();var evaluations=row.putArray(label+"Evaluations");
                    long scoreStart=System.nanoTime();int scored=0;
                    for(int i=0;i<questions.size();i++) {
                        var q=questions.get(i);String answer=c.answer(q.type(),strong);long turnStart=System.nanoTime();
                        var turn=new InterviewService.Answer(i,q.text(),answer,null,null,null,q.type(),q.difficulty(),q.source(),null);ObjectNode evaluation;
                        try {evaluation=model.score("live-fictional",initial,turn,"",c.jd());scored++;}
                        catch(RuntimeException ex) {evaluation=model.unavailable("Live attempt failed; see errorTypes");evaluation.set("errorTypes",errorTypes(ex));}
                        var item=evaluations.addObject().put("index",i).put("answer",answer).put("durationMs",elapsed(turnStart));item.set("evaluation",evaluation);
                        answers.add(new InterviewService.Answer(i,q.text(),answer,null,null,null,q.type(),q.difficulty(),q.source(),evaluation));
                    }
                    row.put(label+"Scored",scored).put(label+"ScoreMs",elapsed(scoreStart));var completed=session(c,answers,true);
                    row.put(label+"ImprovedAnswers",answers.stream().filter(a -> "model".equals(a.evaluation().path("source").asText())
                        && !a.evaluation().path("improvedAnswer").asText().isBlank()).count());
                    var report=InterviewGenerationService.aggregate(completed);row.set(label+"Aggregate",JSON.valueToTree(report));row.set(label+"MeanScore",JSON.valueToTree(report.overall()));
                    long reportStart=System.nanoTime();
                    if(report.overall()!=null) {
                        var input=JSON.createObjectNode().put("role",c.role());input.set("report",JSON.valueToTree(report));
                        try {row.set(label+"Narrative",model.narrative("live-fictional",completed,input));row.put(label+"ReportStatus","MODEL");}
                        catch(RuntimeException ex) {row.put(label+"ReportStatus","AGGREGATE_ONLY");row.set(label+"ReportErrorTypes",errorTypes(ex));}
                    } else row.put(label+"ReportStatus","UNAVAILABLE");
                    row.put(label+"ReportMs",elapsed(reportStart));
                }
                row.put("status",row.path("goodScored").asInt()==questions.size() && row.path("badScored").asInt()==questions.size()
                    && row.path("goodReportStatus").asText().equals("MODEL") && row.path("badReportStatus").asText().equals("MODEL")?"DELIVERED":"PARTIAL");
                row.put("goodAboveBad",row.path("goodMeanScore").isNumber() && row.path("badMeanScore").isNumber() && row.path("goodMeanScore").asInt()>row.path("badMeanScore").asInt());
            } catch(RuntimeException ex) {row.put("questionAttemptMs",elapsed(start)).put("status","BLOCKED");row.set("errorTypes",errorTypes(ex));}
            finally {
                row.put("primaryCalls",gateway.calls.get()-calls);
                Files.writeString(run.resolve(c.role()+".json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(row));
                Files.writeString(run.resolve("summary.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(results));
                Files.writeString(run.resolve("summary.md"),markdown(results));
                System.out.println("INTERVIEW_LIVE role="+c.role()+" status="+row.path("status").asText()+" calls="+row.path("primaryCalls"));
            }
        }
        // Never let assertions echo raw answers into repository-local Surefire reports.
        var failures=new ArrayList<String>();
        for(var r:results) {
            String role=r.path("role").asText();
            if(!r.path("status").asText().equals("DELIVERED")) failures.add(role+": "+r.path("status").asText());
            else if(!r.path("countMatch").asBoolean() || !r.path("typeMixMatch").asBoolean()
                || r.path("techFlagCount").asInt()!=0 || !r.path("goodAboveBad").asBoolean()) failures.add(role+": quality flags require review");
        }
        assertThat(failures).isEmpty();
    }
    static InterviewService.Session session(Scenario c,List<InterviewService.Answer> answers,boolean complete) {
        return new InterviewService.Session("live-fictional",c.role(),c.focus(),0,complete,"","",answers,null,c.options(),"model");
    }
    static long elapsed(long start) {return (System.nanoTime()-start)/1_000_000;}
    static ArrayNode errorTypes(Throwable error) {
        var types=JSON.createArrayNode();var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        while(error!=null && seen.add(error)) {types.add(error.getClass().getSimpleName());error=error.getCause();}return types;
    }
    static String markdown(ArrayNode rows) {
        var md=new StringBuilder("# 跨行业真模型冒烟\n\n真实 Intelligence → ModelGateway FLASH → aggregate → narrative；不覆盖数据库、HTTP及后台任务。每题同题好/差回答各评分一次，各自生成报告。关键词需人工复核；固定回答可能未完全回答随机题。BLOCKED 耗时为失败尝试耗时，不能当模型性能。primaryCalls 不含网关内部安全分类请求，两者均可能收费。\n\n|岗位|状态|题数请求/实际|技术标记|好/差均分|出题ms|好/差评分总ms|好/差报告ms|15题fallback去重|\n|---|---|---|---|---|---|---|---|---|\n");
        for(var r:rows) md.append("| ").append(r.path("role").asText()).append(" | ").append(r.path("status").asText()).append(" | ").append(r.path("requestedCount")).append("/").append(r.path("actualCount").asText("未测")).append(" | ").append(r.path("techFlagCount").asText("未测")).append(" | ").append(r.path("goodMeanScore").asText("未测")).append("/").append(r.path("badMeanScore").asText("未测")).append(" | ").append(r.path("questionMs").asText("未测")).append(" | ").append(r.path("goodScoreMs").asText("未测")).append("/").append(r.path("badScoreMs").asText("未测")).append(" | ").append(r.path("goodReportMs").asText("未测")).append("/").append(r.path("badReportMs").asText("未测")).append(" | ").append(r.path("fallback15UniqueCount")).append("/15 |\n");
        return md.toString();
    }
    static final class RecordingGateway extends ModelGateway {
        final Path directory;final AtomicInteger calls=new AtomicInteger();
        RecordingGateway(Path directory) {
            super(new OfferPilotProperties(null,null,null,null,null,new OfferPilotProperties.Llm("FLASH",Map.of("FLASH",
                new OfferPilotProperties.Llm.Tier("deepseek","deepseek-flash",Duration.ofSeconds(60),true,"disabled",8192)),Map.of()),null,null),
                new MockEnvironment().withProperty("spring.ai.deepseek.base-url",System.getenv().getOrDefault("DEEPSEEK_BASE_URL","https://api.deepseek.com"))
                    .withProperty("spring.ai.deepseek.api-key",System.getenv("DEEPSEEK_API_KEY")),null,noRetry());this.directory=directory;
        }
        static RetryTemplate noRetry() {var retry=new RetryTemplate();retry.setRetryPolicy(new NeverRetryPolicy());return retry;}
        @Override public Flux<ChatResponse> stream(ModelTier tier,List<Message> messages,double temperature) {
            return Flux.defer(() -> {
                int call=calls.incrementAndGet();if(call>180) return Flux.error(new IllegalStateException("Live primary request budget exhausted"));
                var raw=new StringBuilder();
                return super.stream(tier,messages,temperature).doOnNext(r -> {if(r.getResult()!=null && r.getResult().getOutput().getText()!=null && raw.length()<48000) raw.append(r.getResult().getOutput().getText());})
                    .doFinally(signal -> {
                        try {Files.writeString(directory.resolve("attempt-"+call+".json"),JSON.writeValueAsString(Map.of("call",call,"signal",signal.name(),
                            "raw",raw.toString().replace(System.getenv("DEEPSEEK_API_KEY"),"[REDACTED]"))));}
                        catch(java.io.IOException ex) {throw new java.io.UncheckedIOException(ex);}
                    });
            });
        }
    }
}
