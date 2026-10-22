package com.offerpilot.agent.tools;

import com.fasterxml.jackson.databind.*;
import com.offerpilot.agent.domain.*;
import com.offerpilot.agent.event.AgentEvent.ToolAccess;
import com.offerpilot.common.error.*;
import java.util.*;

/** Closed, local JSON Schema vocabulary. Unknown tools/properties fail closed before approval or effects. */
public final class ToolContracts {
    private static final ObjectMapper JSON = new ObjectMapper();
    public record Contract(ExpertType expert, String action, ToolAccess access, JsonNode schema) {}
    private static final Map<String, Contract> ALL = new LinkedHashMap<>();
    static {
        read(ExpertType.JOB, "search", "query site nature location");
        write(ExpertType.JOB, "match", "", "");
        read(ExpertType.JOB, "getApplicationDetail", "applicationId");
        write(ExpertType.JOB, "createApplication", "jobId status note nextAction nextActionAt", "jobId");
        write(ExpertType.JOB, "updateApplicationStatus", "applicationId status note nextAction nextActionAt", "applicationId");
        for (String a : List.of("advice", "read_optimization")) read(ExpertType.RESUME,a, "");
        for (String a : List.of("diagnose", "diagnosis", "optimize")) write(ExpertType.RESUME,a,"", "");
        write(ExpertType.RESUME,"applyPatches", "resumeId resumeVersionId batchId accepted edited confirmed", "resumeId resumeVersionId batchId accepted");
        read(ExpertType.CAREER_DISCOVERY,"recommend", "request");
        for (String a : List.of("setCareerTarget", "adoptTargetCareer")) write(ExpertType.CAREER_DISCOVERY,a,"occupationId name", "");
        write(ExpertType.CAREER_DISCOVERY,"rerankCareers", "revisionId city minSalaryK avoidOvertime research", "revisionId");
        read(ExpertType.COACH,"planLearning", "");
        write(ExpertType.COACH,"adoptGrowthPlan", "taskTitles", "taskTitles");
        write(ExpertType.INTERVIEWER,"startInterview", "role kind jobId", "");
        for (String a : List.of("continueInterview", "answerInterview")) write(ExpertType.INTERVIEWER,a,"sessionId answer nextQuestion expectedUpdatedAt", "answer");
        write(ExpertType.INTERVIEWER,"completeInterview", "sessionId expectedUpdatedAt", "");
        write(ExpertType.INTERVIEWER,"saveStarStory", "sessionId questionIndex", "sessionId questionIndex");
        for (String a : List.of("scoreInterview", "generateReport", "completedInterview", "recallStarStories", "noteGuidance", "interviewGuidance")) read(ExpertType.INTERVIEWER,a,"sessionId");
        for (ExpertType e : ExpertType.values()) {
            read(e,"clarify","question");
            read(e,"default","request");
        }
        // This tool runs outside the plan, but uses the same pre-approval validation.
        write(null,"saveUserFact","kind fact source", "kind fact source");
    }
    private ToolContracts() {}
    public static Collection<Contract> all() { return List.copyOf(ALL.values()); }
    private static String key(ExpertType e,String a) { return e+":"+a; }
    private static void read(ExpertType e,String a,String fields) { register(e,a,ToolAccess.READ,fields,""); }
    private static void write(ExpertType e,String a,String fields,String required) { register(e,a,ToolAccess.WRITE,fields,required); }
    private static void register(ExpertType e,String a,ToolAccess access,String fields,String required) {
        var schema=JSON.createObjectNode().put("$schema","https://json-schema.org/draft/2020-12/schema")
                .put("type","object").put("additionalProperties",false);
        var props=schema.putObject("properties");
        props.putObject("action").put("const",a);
        for(String f : (fields+" noteQuery jobFrom resumeFrom").split(" +")) {
            if(f.isBlank()) continue;
            var p=props.putObject(f);
            switch(f) {
                case "accepted", "confirmed", "taskTitles" -> {
                    p.put("type","array").put("maxItems",100);
                    p.putObject("items").put("type","string").put("minLength",1).put("maxLength",500);
                }
                case "edited" -> { p.put("type","object"); var item=p.putObject("additionalProperties").put("type","object");
                    item.putObject("additionalProperties").put("type","string").put("maxLength",10000); }
                case "nextQuestion", "avoidOvertime", "research" -> p.put("type","boolean");
                case "questionIndex" -> p.put("type","integer").put("minimum",0).put("maximum",4);
                case "minSalaryK" -> p.put("type","integer").put("minimum",0).put("maximum",1000);
                default -> p.put("type","string").put("maxLength", switch(f) {
                    case "request", "answer" -> 20000;
                    case "note", "noteQuery" -> 2000;
                    case "question", "fact" -> 4000;
                    default -> 500;
                });
            }
        }
        var req=schema.putArray("required");
        for(String f: required.split(" +")) if(!f.isBlank()) req.add(f);
        ALL.put(key(e,a),new Contract(e,a,access,schema));
    }
    public static Contract contract(PlanStep step) {
        Object action=step.inputs().getOrDefault("action","default");
        Contract c=action instanceof String a ? ALL.get(key(step.agent(),a)) : null;
        if(c==null) throw invalid("$.action", "registered tool required");
        return c;
    }
    public static void validate(PlanStep step) { validate(contract(step).schema(), JSON.valueToTree(step.inputs()),"$"); }
    public static void validateApproved(String action,Map<String,Object> args) {
        var contract=ALL.values().stream().filter(c -> c.action().equals(action) && c.access()==ToolAccess.WRITE).findFirst()
                .orElseThrow(() -> invalid("$.action","registered write tool required"));
        var values=new LinkedHashMap<String,Object>(args);values.put("action",action);
        validate(contract.schema(),JSON.valueToTree(values),"$");
    }
    public static void validateMemory(Map<String,Object> args) { validate(ALL.get(key(null,"saveUserFact")).schema(),JSON.valueToTree(args),"$"); }
    public static void validate(JsonNode s,JsonNode v,String path) {
        if(s.has("const") && !s.get("const").equals(v)) throw invalid(path,"const");
        if(s.has("const") && !s.has("type")) return;
        String t=s.path("type").asText();
        boolean ok=switch(t) {
            case "object" -> v.isObject(); case "array" -> v.isArray(); case "string" -> v.isTextual();
            case "integer" -> v.isIntegralNumber(); case "boolean" -> v.isBoolean(); default -> false;
        };
        if(!ok) throw invalid(path,t+" required");
        if(v.isObject()) {
            for(JsonNode r:s.path("required")) if(!v.has(r.asText())) throw invalid(path+"."+r.asText(),"required");
            v.fields().forEachRemaining(f -> {
                JsonNode child=s.path("properties").get(f.getKey());
                if(child==null) {
                    child=s.get("additionalProperties");
                    if(child==null || child.isBoolean()) throw invalid(path,"additionalProperties");
                }
                validate(child,f.getValue(),path+"."+f.getKey());
            });
        }
        if(v.isTextual() && (v.textValue().codePointCount(0,v.textValue().length())>s.path("maxLength").asInt(Integer.MAX_VALUE)
                || v.textValue().length()<s.path("minLength").asInt(0))) throw invalid(path,"length");
        if(v.isNumber() && (v.asDouble()<s.path("minimum").asDouble(-Double.MAX_VALUE) || v.asDouble()>s.path("maximum").asDouble(Double.MAX_VALUE))) throw invalid(path,"range");
        if(v.isArray()) {
            if(v.size()>s.path("maxItems").asInt(Integer.MAX_VALUE)) throw invalid(path,"maxItems");
            for(int i=0;i<v.size();i++) validate(s.path("items"),v.get(i),path+"["+i+"]");
        }
    }
    private static BusinessException invalid(String path,String rule) {
        // Paths and rule names only; never reflect untrusted values into model context/logs.
        return new BusinessException(ErrorCode.VALIDATION_FAILED,"TOOL_ARGUMENT_INVALID path="+path+" rule="+rule);
    }
}
