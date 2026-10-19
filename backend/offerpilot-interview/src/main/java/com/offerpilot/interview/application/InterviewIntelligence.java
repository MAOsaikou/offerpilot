package com.offerpilot.interview.application;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.offerpilot.common.artifact.BackgroundWork;
import com.offerpilot.common.error.*;
import com.offerpilot.common.text.NumericProvenance;
import com.offerpilot.llm.*;
import com.offerpilot.llm.quota.LlmUserContext;
import org.springframework.ai.chat.messages.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;

/** Model I/O and bounded evidence validation; no database transaction spans a model call. */
@Component
public class InterviewIntelligence {
    public static final List<String> DIMENSIONS = List.of("professional", "expression", "logic", "jobFit");
    private final ObjectProvider<ModelGateway> gateways;
    private final ObjectMapper json;
    private final String questionsPrompt, scoringPrompt, reportPrompt;
    public InterviewIntelligence(ObjectProvider<ModelGateway> gateways, ObjectMapper json,
            @Value("classpath:prompts/interview/questions.st") Resource questions,
            @Value("classpath:prompts/interview/score.st") Resource scoring,
            @Value("classpath:prompts/interview/report.st") Resource report) throws java.io.IOException {
        this.gateways=gateways; this.json=json;
        questionsPrompt=questions.getContentAsString(StandardCharsets.UTF_8);
        scoringPrompt=scoring.getContentAsString(StandardCharsets.UTF_8);
        reportPrompt=report.getContentAsString(StandardCharsets.UTF_8);
    }
    public ObjectNode input(InterviewService.Session session, String resume, String jd) {
        var input=json.createObjectNode().put("role",session.role()).put("industry",session.options().industry()).put("focus",session.kind())
                .put("resume",Objects.toString(resume,"")).put("jobDescription",Objects.toString(jd,""));
        input.set("options",json.valueToTree(session.options()));
        return input;
    }
    private <T> T generate(String owner, String prompt, JsonNode input, Function<JsonNode,T> validate) {
        var gateway=gateways.getIfAvailable();
        if (gateway==null) throw new BusinessException(ErrorCode.MODEL_UNAVAILABLE);
        gateway.requireAvailable(ModelTier.FLASH,false);
        for (int attempt=0; attempt<2; attempt++) {
            BackgroundWork.check();
            var messages=List.<Message>of(new SystemMessage(prompt+(attempt==0?"":"\n上次结构或证据校验失败。严格遵守JSON结构与逐字引用约束，重新输出。")),new UserMessage(input.toString()));
            String raw=LlmUserContext.asUser(owner, () -> gateway.stream(ModelTier.FLASH,messages,0.3)
                .map(r -> r.getResult()==null || r.getResult().getOutput().getText()==null ? "" : r.getResult().getOutput().getText())
                .reduce(new StringBuilder(),(all,part) -> {
                    if (all.length()+part.length()>48000) throw invalid();
                    return all.append(part);
                }).map(StringBuilder::toString).block(Duration.ofSeconds(90)));
            BackgroundWork.check();
            try {
                if (raw==null) throw invalid();
                String text=raw.strip().replaceFirst("^```(?:json)?\\s*","").replaceFirst("\\s*```$","");
                return validate.apply(json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text));
            } catch (java.io.IOException | IllegalArgumentException | BusinessException ex) {
                if (attempt==1) throw invalid();
            }
        }
        throw invalid();
    }
    public record Question(String text,String type,String difficulty,String source) {}
    public List<Question> questions(String owner, InterviewService.Session session, String resume, String jd) {
        var input=input(session,resume,jd);
        return generate(owner,questionsPrompt,input,output -> validateQuestions(output,session.options()));
    }
    public List<Question> validateQuestions(JsonNode output, InterviewOptions options) {
        require(output!=null && output.isObject());
        var questions=output.path("questions");
        require(questions.isArray() && questions.size()==options.questionCount());
        var result=new ArrayList<Question>(); var seen=new HashSet<String>(); var mix=new HashMap<String,Integer>();
        for (var q:questions) {
            String text=text(q,"text",1000), type=text(q,"type",30), difficulty=text(q,"difficulty",20);
            require(text.length()>=6 && seen.add(text) && InterviewOptions.TYPES.contains(type) && difficulty.equals(options.difficulty()));
            mix.merge(type,1,Integer::sum);
            result.add(new Question(text,type,difficulty,"model"));
        }
        if (!options.typeMix().isEmpty()) for(String type:InterviewOptions.TYPES)
            require(mix.getOrDefault(type,0).equals(options.typeMix().getOrDefault(type,0)));
        return List.copyOf(result);
    }
    public ObjectNode score(String owner, InterviewService.Session session, InterviewService.Answer answer, String resume, String jd) {
        var input=input(session,resume,jd);
        input.put("question",answer.questionText()).put("type",answer.questionType()).put("answer",answer.fullText());
        return generate(owner,scoringPrompt,input,output -> validateScore(output,answer.fullText(),Objects.toString(resume,"")));
    }
    public ObjectNode validateScore(JsonNode output, String answer, String resume) {
        require(output!=null && output.isObject());
        var dimensions=output.path("dimensions"); require(dimensions.isObject() && dimensions.size()==4);
        int total=0;
        for (String name:DIMENSIONS) {
            var d=dimensions.path(name);
            require(d.path("score").isIntegralNumber() && d.path("score").canConvertToInt() && d.path("score").asInt()>=0 && d.path("score").asInt()<=100);
            text(d,"rationale",1000);
            String evidence=text(d,"evidence",1000); require(answer.contains(evidence));
            total+=d.path("score").asInt();
        }
        String rationale=text(output,"rationale",1500);
        var parts=output.path("improvedAnswerParts"); require(parts.isArray() && !parts.isEmpty() && parts.size()<=12);
        String source=answer+"\n"+resume; var improved=new ArrayList<String>();
        for (var part:parts) {
            String section=text(part,"section",20);
            require(Set.of("背景","任务","行动","结果","思路","岗位关联").contains(section));
            String quote=part.path("quote").asText(""); String placeholder=part.path("placeholder").asText("");
            require(!quote.isBlank() || !placeholder.isBlank());
            // Only exact source excerpts may assert candidate facts. Free model prose is never promoted to experience.
            require(quote.length()<=4000 && (quote.isEmpty() || source.contains(quote)));
            if (!placeholder.isEmpty()) {
                require(placeholder.matches("【补充[^【】\\r\\n]{1,100}】"));
                if (!NumericProvenance.numbers(source).containsAll(NumericProvenance.numbers(placeholder))) placeholder="【补充你的具体数据】";
            }
            improved.add(section+"："+quote+(placeholder.isEmpty()?"":" "+placeholder));
        }
        var result=json.createObjectNode().put("source","model").put("score",total/4).put("rationale",rationale)
                .put("improvedAnswer",String.join("\n",improved)).put("provenance","原文摘录重组；缺失事实用占位符，未补造经历或数字");
        result.set("dimensions",dimensions.deepCopy());
        result.set("strengths",strings(output,"strengths",true)); result.set("weaknesses",strings(output,"weaknesses",true));
        return result;
    }
    public ObjectNode unavailable(String reason) {
        return json.createObjectNode().put("source","unavailable").putNull("score").put("rationale",reason)
                .put("improvedAnswer","【补充真实背景与职责】\n【补充你的具体行动】\n【补充真实结果或验证方式】");
    }
    public ObjectNode narrative(String owner, InterviewService.Session session, JsonNode aggregation) {
        return generate(owner,reportPrompt,aggregation,output -> {
            var result=json.createObjectNode();
            for(String name:List.of("strengths","weaknesses","advice")) result.set(name,strings(output,name,false));
            return result;
        });
    }
    private ArrayNode strings(JsonNode output,String name,boolean allowEmpty) {
        var list=output.path(name); require(list.isArray() && list.size()<=8 && (allowEmpty || !list.isEmpty()));
        for(var item:list) require(item.isTextual() && !item.asText().isBlank() && item.asText().length()<=1000);
        return (ArrayNode)list.deepCopy();
    }
    private static String text(JsonNode node,String field,int max) {
        var value=node.path(field); require(value.isTextual() && !value.asText().isBlank() && value.asText().length()<=max); return value.asText();
    }
    private static void require(boolean ok) { if (!ok) throw invalid(); }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.STRUCTURED_OUTPUT_INVALID,"面试模型输出的结构或事实依据未通过校验"); }
}
