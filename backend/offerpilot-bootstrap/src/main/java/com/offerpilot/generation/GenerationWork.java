package com.offerpilot.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.career.application.CareerAssessmentService;
import com.offerpilot.career.application.CareerRecommendationService;
import com.offerpilot.interview.application.InterviewService;
import com.offerpilot.job.application.JobImportService;
import com.offerpilot.job.application.JobMatchService;
import com.offerpilot.job.application.JobService;
import com.offerpilot.resume.application.ResumeDiagnosisService;
import com.offerpilot.resume.application.ResumeOptimizationService;
import com.offerpilot.resume.application.ResumeService;
import com.offerpilot.llm.ModelTier;
import com.offerpilot.common.error.*;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.function.Consumer;

/** Bootstrap composes application services without reverse dependencies between feature modules. */
@Component
public class GenerationWork {
    public static final Set<String> KINDS = Set.of("RESUME_DIAGNOSIS", "RESUME_OPTIMIZE", "JOB_MATCH",
            "CAREER_ASSESSMENT", "CAREER_RECOMMENDATION", "INTERVIEW_FOLLOW_UP", "JOB_IMPORT");
    private final ResumeDiagnosisService diagnoses;
    private final ResumeOptimizationService optimizations;
    private final JobMatchService matches;
    private final CareerAssessmentService assessments;
    private final CareerRecommendationService careers;
    private final InterviewService interviews;
    private final JobImportService imports;
    private final ResumeService resumes;
    private final JobService jobs;
    private final ObjectMapper json;

    public GenerationWork(ResumeDiagnosisService diagnoses, ResumeOptimizationService optimizations,
            JobMatchService matches, CareerAssessmentService assessments, CareerRecommendationService careers,
            InterviewService interviews, JobImportService imports, ResumeService resumes, JobService jobs, ObjectMapper json) {
        this.diagnoses=diagnoses; this.optimizations=optimizations; this.matches=matches; this.assessments=assessments;
        this.careers=careers; this.interviews=interviews; this.imports=imports; this.resumes=resumes; this.jobs=jobs; this.json=json;
    }
    public void validate(String owner, GenerationTaskService.Start body) {
        if (body == null || body.kind() == null || !KINDS.contains(body.kind()) || body.entityId() == null
                || !body.entityId().matches("[a-zA-Z0-9_-]{1,80}") || body.requestId() == null
                || !body.requestId().matches("[a-zA-Z0-9-]{16,80}") || body.input() == null
                || !body.input().isObject() || body.input().toString().length() > 25000)
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        var input=body.input();
        switch (body.kind()) {
            case "RESUME_DIAGNOSIS", "RESUME_OPTIMIZE" -> resumes.version(owner, body.entityId(), required(input,"versionId"));
            case "JOB_MATCH" -> { jobs.get(owner,body.entityId()); resumes.version(owner,required(input,"resumeId"),required(input,"versionId")); }
            case "INTERVIEW_FOLLOW_UP" -> {
                interviews.get(owner, body.entityId());
                if (!input.path("index").isIntegralNumber() || !input.path("index").canConvertToInt()
                        || input.path("index").asInt()<0 || input.path("index").asInt()>9)
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            case "CAREER_ASSESSMENT", "CAREER_RECOMMENDATION", "JOB_IMPORT" -> {
                if (!"current".equals(body.entityId())) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (body.kind().equals("JOB_MATCH") && input.hasNonNull("modelTier")) tier(input);
    }
    private String required(JsonNode input, String name) {
        if (!input.path(name).isTextual() || !input.path(name).asText().matches("[a-zA-Z0-9_-]{1,80}"))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return input.path(name).asText();
    }
    private ModelTier tier(JsonNode input) {
        try { return input.hasNonNull("modelTier") ? ModelTier.valueOf(input.path("modelTier").asText()) : null; }
        catch (IllegalArgumentException e) { throw new BusinessException(ErrorCode.VALIDATION_FAILED); }
    }
    public JsonNode execute(String owner, GenerationTaskService.Start b, Consumer<String> reference) {
        var i=b.input();
        return switch (b.kind()) {
            case "RESUME_DIAGNOSIS" -> {
                var work=diagnoses.begin(owner,b.entityId(),i.path("versionId").asText(),b.requestId());
                reference.accept(work.id());
                yield diagnoses.execute(work).block();
            }
            case "RESUME_OPTIMIZE" -> {
                var work=optimizations.begin(owner,b.entityId(),i.path("versionId").asText(),
                        new ResumeOptimizationService.Input(b.requestId(),i.path("jobDescription").asText(),i.hasNonNull("jobId")?i.path("jobId").asText():null));
                reference.accept(work.id());
                yield optimizations.execute(work).block();
            }
            case "JOB_MATCH" -> matches.create(owner,b.entityId(),i.path("resumeId").asText(),i.path("versionId").asText(),b.requestId(),tier(i));
            case "CAREER_ASSESSMENT" -> {
                var saved=assessments.infer(owner);
                if (saved.inference()==null) throw new BusinessException(ErrorCode.VALIDATION_FAILED,"回答已改变，请按最新回答重新生成画像");
                yield json.valueToTree(saved);
            }
            case "CAREER_RECOMMENDATION" -> json.valueToTree(careers.create(owner));
            case "INTERVIEW_FOLLOW_UP" -> {
                var saved=interviews.followUp(owner,b.entityId(),i.path("index").asInt());
                if(saved.answers().get(i.path("index").asInt()).followUpQuestion()==null)
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED,"回答已改变，请保存最新回答后重新生成追问");
                yield json.valueToTree(saved);
            }
            case "JOB_IMPORT" -> json.valueToTree(imports.parse(owner,i.hasNonNull("text")?i.path("text").asText():null,i.hasNonNull("url")?i.path("url").asText():null));
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        };
    }
    public String title(String kind) {
        return switch(kind) {
            case "RESUME_DIAGNOSIS" -> "简历诊断"; case "RESUME_OPTIMIZE" -> "简历定向改写";
            case "JOB_MATCH" -> "岗位匹配分析"; case "CAREER_ASSESSMENT" -> "职业画像推断";
            case "CAREER_RECOMMENDATION" -> "职业推荐"; case "INTERVIEW_FOLLOW_UP" -> "面试追问";
            case "JOB_IMPORT" -> "岗位导入解析"; default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        };
    }
    public String href(GenerationTaskService.Start b) {
        return switch(b.kind()) {
            case "RESUME_DIAGNOSIS", "RESUME_OPTIMIZE" -> "/resumes/"+b.entityId()+"?tab="+(b.kind().equals("RESUME_DIAGNOSIS")?"diagnosis":"optimize");
            case "JOB_MATCH" -> "/jobs/"+b.entityId();
            case "INTERVIEW_FOLLOW_UP" -> "/interviews/"+b.entityId();
            case "CAREER_ASSESSMENT" -> "/discover";
            case "CAREER_RECOMMENDATION" -> "/career";
            case "JOB_IMPORT" -> "/jobs";
            default -> throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        };
    }
}
