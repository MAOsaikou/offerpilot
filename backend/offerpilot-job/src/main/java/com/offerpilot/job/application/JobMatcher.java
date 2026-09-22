package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.common.error.*;
import com.offerpilot.llm.ModelGateway;
import org.springframework.ai.chat.messages.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Component
public class JobMatcher {
    private final ModelGateway gateway;
    private final ObjectMapper json;
    private final String prompt;
    public JobMatcher(ModelGateway gateway,ObjectMapper json,@Value("classpath:prompts/job/match.st") Resource prompt) throws java.io.IOException {
        this.gateway=gateway; this.json=json; this.prompt=prompt.getContentAsString(StandardCharsets.UTF_8);
    }
    public JsonNode match(JsonNode job,JsonNode resume) {
        var tier=gateway.defaultTier();
        String jd=JobService.evidenceText(job);
        if(job.path("description").asText().isBlank() && job.path("requirement").asText().isBlank()) throw new BusinessException(ErrorCode.JOB_CONTENT_MISSING);
        var input=json.createObjectNode(); input.put("jobText",jd); input.set("resume",resume);
        if(input.toString().length()>100000) throw new BusinessException(ErrorCode.VALIDATION_FAILED,"简历或岗位内容过长，请使用精简后的简历版本");
        long start=System.nanoTime();
        String output;
        try {
            output=gateway.stream(tier,List.of(new SystemMessage(prompt),new UserMessage(input.toString())),0)
                .map(r->r.getResult()==null || r.getResult().getOutput().getText()==null?"":r.getResult().getOutput().getText())
                .reduce(new StringBuilder(),(all,part)-> { if(all.length()+part.length()>40000) throw new BusinessException(ErrorCode.STRUCTURED_OUTPUT_INVALID); return all.append(part); })
                .map(StringBuilder::toString).block(Duration.ofSeconds(90));
        } catch(BusinessException ex) { throw ex; }
        catch(Exception ex) {
            for(Throwable cause=ex;cause!=null;cause=cause.getCause()) if(cause instanceof java.util.concurrent.TimeoutException)
                throw new BusinessException(ErrorCode.MODEL_TIMEOUT,"匹配分析超时，请稍后重试");
            throw new BusinessException(ErrorCode.MODEL_UNAVAILABLE);
        }
        try {
            String clean=output==null?"":output.strip().replaceFirst("^```(?:json)?\\s*","").replaceFirst("\\s*```$","");
            JsonNode report=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(clean);
            validate(report,jd,resume);
            var result=(ObjectNode)report;
            int points=0;
            for(var item:report.path("requirements")) points+=switch(item.path("status").asText()) { case "MET"->2; case "PARTIAL"->1; default->0; };
            result.put("score",Math.round(100f*points/(2*report.path("requirements").size())));
            result.put("model",gateway.modelName(tier)); result.put("promptVersion","job-match-v1");
            result.put("durationMs",(System.nanoTime()-start)/1000000);
            return result;
        } catch(BusinessException ex) { throw ex; }
        catch(Exception ex) { throw invalid(); }
    }
    static void validate(JsonNode report,String jd,JsonNode resume) {
        if(!report.isObject() || !text(report,"summary",2000) || !report.path("requirements").isArray()
                || report.path("requirements").isEmpty() || report.path("requirements").size()>12) throw invalid();
        Set<String> quotes=new HashSet<>();
        for(var item:report.path("requirements")) {
            if(!text(item,"requirement",500) || !text(item,"jdQuote",1500) || !text(item,"reason",1500) || !text(item,"suggestion",1500)) throw invalid();
            String quote=item.path("jdQuote").asText();
            if(!jd.contains(quote) || !quotes.add(quote) || !Set.of("MET","PARTIAL","MISSING").contains(item.path("status").asText())) throw invalid();
            if(!item.path("resumePath").isTextual() || !item.path("resumeQuote").isTextual()) throw invalid();
            String path=item.path("resumePath").asText(), evidence=item.path("resumeQuote").asText();
            if("MISSING".equals(item.path("status").asText())) { if(!path.isEmpty() || !evidence.isEmpty()) throw invalid(); }
            else {
                if(!path.startsWith("/") || evidence.isBlank() || evidence.length()>1500) throw invalid();
                JsonNode source;
                try { source=resume.at(path); } catch(Exception ex) { throw invalid(); }
                if(!source.isTextual() || !source.asText().contains(evidence)) throw invalid();
            }
        }
    }
    private static boolean text(JsonNode node,String key,int max) { return node.path(key).isTextual() && !node.path(key).asText().isBlank() && node.path(key).asText().length()<=max; }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.STRUCTURED_OUTPUT_INVALID,"匹配报告的原文依据校验未通过，请重试；未保存未经核实的报告"); }
}
