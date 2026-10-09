package com.offerpilot.agent.orchestrator;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.domain.*;
import com.offerpilot.agent.experts.ExpertRegistry;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Component
public class IntentPlanner {
    public record Decision(String kind, ExpertType expert, Double confidence, boolean needsClarification,
                           String clarifyingQuestion, Plan plan) {}
    private final ModelGateway gateway;
    private final ObjectMapper mapper;
    private final ExpertRegistry experts;
    private final String prompt;
    private final JobIntentParser jobs;
    private final ResumeIntentParser resumes;

    public IntentPlanner(ModelGateway gateway, ObjectMapper mapper, ExpertRegistry experts,
                         Resource prompt) throws IOException {
        this(gateway, mapper, experts, prompt, null);
    }

    public IntentPlanner(ModelGateway gateway, ObjectMapper mapper, ExpertRegistry experts,
                         @Value("classpath:prompts/agent/plan.st") Resource prompt, JobIntentParser jobs) throws IOException {
        this(gateway, mapper, experts, prompt, jobs, null);
    }

    @Autowired
    public IntentPlanner(ModelGateway gateway, ObjectMapper mapper, ExpertRegistry experts,
                         @Value("classpath:prompts/agent/plan.st") Resource prompt, JobIntentParser jobs,
                         ResumeIntentParser resumes) throws IOException {
        this.gateway = gateway;
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.experts = experts;
        this.prompt = prompt.getContentAsString(StandardCharsets.UTF_8);
        this.jobs = jobs;
        this.resumes = resumes;
    }

    public Mono<Decision> prepare(AgentMode mode, String content, List<Message> history, ModelTier tier,
                                  Consumer<Usage> onUsage, Map<String, Object> context) {
        Map<String, Object> safeContext = context == null ? Map.of() : context;
        if (safeContext.containsKey("jobAction")) {
            if (!"match".equals(safeContext.get("jobAction"))
                    || mode != null && mode != AgentMode.AUTO && mode != AgentMode.JOB
                    || List.of("boardAction", "resumeAction", "careerAction", "interviewAction", "coachAction")
                            .stream().anyMatch(safeContext::containsKey))
                return Mono.error(new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "匹配动作无效：请在岗位分析或智能助手中单独发起匹配。"));
            if (!SelectedResources.matchReady(safeContext))
                return Mono.error(new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "未执行匹配：请先选择岗位、已保存的简历和简历版本。"));
            // A caller action is authoritative. Do not ask either planning model to classify it again.
            return Mono.just(new Decision("single", ExpertType.JOB, 1.0, false, null,
                    PlanValidator.validate(new Plan(content, List.of(new PlanStep("s1", "岗位与简历匹配",
                            ExpertType.JOB, Map.of("action", "match"), List.of()))))));
        }
        if ((mode == null || mode == AgentMode.AUTO || mode == AgentMode.JOB) && BoardIntent.matches(content, safeContext)) {
            return resolveActions(new Decision("single", ExpertType.JOB, 1.0, false, null,
                    single(content, ExpertType.JOB, safeContext)), content, history, tier, onUsage, safeContext);
        }
        if ((mode == null || mode == AgentMode.AUTO || mode == AgentMode.RESUME)
                && "applyPatches".equals(safeContext.get("resumeAction"))) {
            return resolveActions(new Decision("single", ExpertType.RESUME, 1.0, false, null,
                    single(content, ExpertType.RESUME, safeContext)), content, history, tier, onUsage, safeContext);
        }
        if ((mode == null || mode == AgentMode.AUTO || mode == AgentMode.INTERVIEWER)
                && (safeContext.containsKey("interviewAction") || safeContext.containsKey("interviewSessionId"))) {
            return resolveActions(new Decision("single", ExpertType.INTERVIEWER, 1.0, false, null,
                    single(content, ExpertType.INTERVIEWER, safeContext)), content, history, tier, onUsage, safeContext);
        }
        if ((mode == null || mode == AgentMode.AUTO || mode == AgentMode.CAREER_DISCOVERY)
                && "setCareerTarget".equals(safeContext.get("careerAction"))) {
            return resolveActions(new Decision("single", ExpertType.CAREER_DISCOVERY, 1.0, false, null,
                    single(content, ExpertType.CAREER_DISCOVERY, safeContext)), content, history, tier, onUsage, safeContext);
        }
        if ((mode == null || mode == AgentMode.AUTO || mode == AgentMode.COACH)
                && (safeContext.containsKey("coachAction") || CoachIntentParser.matches(content))) {
            return resolveActions(new Decision("single", ExpertType.COACH, 1.0, false, null,
                    single(content, ExpertType.COACH, safeContext)), content, history, tier, onUsage, safeContext);
        }
        if (mode != null && mode != AgentMode.AUTO) {
            ExpertType expert = ExpertType.valueOf(mode.name());
            return resolveActions(new Decision("single", expert, 1.0, false, null, single(content, expert, safeContext)),
                    content, history, tier, onUsage, safeContext);
        }
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(prompt.replace("{experts}", experts.capabilities())));
        messages.addAll(history.subList(Math.max(0, history.size() - 12), history.size()));
        messages.add(new UserMessage(SelectedResources.describe(safeContext) + "\n本轮请求：" + content));
        return attempt(messages, content, tier, onUsage, 0, safeContext)
                .flatMap(decision -> resolveActions(decision, content, history, tier, onUsage, safeContext));
    }

    private Mono<Decision> resolveActions(Decision decision, String content, List<Message> history, ModelTier tier,
                                        Consumer<Usage> onUsage, Map<String, Object> context) {
        if (decision.plan() == null) return Mono.just(decision);
        return Flux.fromIterable(decision.plan().steps()).concatMap(step -> {
            if (step.agent() == ExpertType.COACH) return Mono.just(CoachIntentParser.prepare(step, content, context));
            if (step.agent() == ExpertType.INTERVIEWER) return Mono.just(InterviewIntentParser.prepare(step, content, context));
            if (step.agent() == ExpertType.CAREER_DISCOVERY) return Mono.just(CareerIntentParser.prepare(step, content, context));
            if (step.agent() == ExpertType.JOB && jobs != null) return jobs.prepare(step, content, history, tier, onUsage, context);
            if (step.agent() == ExpertType.RESUME && resumes != null) return resumes.prepare(step, content, history, tier, onUsage, context);
            return Mono.just(step);
        }).map(step -> {
            // Retrieval query comes from this turn's user text, never model-generated inputs or IDs.
            if (step.agent() == ExpertType.INTERVIEWER
                    || step.agent() == ExpertType.COACH && !experts.writingCoach(step)
                    || step.agent() == ExpertType.RESUME && "advice".equals(step.inputs().get("action"))) {
                var inputs = new LinkedHashMap<>(step.inputs());
                inputs.put("noteQuery", com.offerpilot.knowledge.application.KnowledgeNoteSearch.bounded(content, 2000));
                return new PlanStep(step.id(), step.title(), step.agent(), inputs, step.dependsOn());
            }
            return step;
        }).collectList().map(steps -> {
            for (var step : steps) {
                if ("clarify".equals(step.inputs().get("action"))) return new Decision(decision.kind(), decision.expert(),
                        decision.confidence(), true, (String) step.inputs().get("question"), null);
            }
            return new Decision(decision.kind(), decision.expert(), decision.confidence(), false, null,
                    PlanValidator.validate(new Plan(decision.plan().goal(), steps)));
        });
    }

    private Mono<Decision> attempt(List<Message> messages, String content, ModelTier tier,
                                   Consumer<Usage> onUsage, int retry, Map<String, Object> context) {
        return Mono.defer(() -> {
            AtomicReference<Usage> usage = new AtomicReference<>();
            return gateway.stream(tier, messages, 0.1).doOnNext(chunk -> {
                var current = chunk.getMetadata().getUsage();
                if (current != null && current.getTotalTokens() != null && current.getTotalTokens() > 0) usage.set(current);
            }).map(chunk -> chunk.getResult() == null || chunk.getResult().getOutput() == null || chunk.getResult().getOutput().getText() == null
                    ? "" : chunk.getResult().getOutput().getText())
                    .reduce(new StringBuilder(), (text, delta) -> {
                        if (text.length() + delta.length() > 16000) throw new IllegalArgumentException("规划输出过长");
                        return text.append(delta);
                    }).timeout(Duration.ofSeconds(60))
                    .doOnSuccess(ignored -> { if (usage.get() != null) onUsage.accept(usage.get()); })
                    .map(text -> decode(text.toString(), content, context));
        }).onErrorResume(IllegalArgumentException.class, ex -> {
            if (retry >= 2) return Mono.just(new Decision("fallback", null, 0.0, false, null, null));
            List<Message> corrected = new ArrayList<>(messages);
            corrected.add(new SystemMessage("上次输出不符合 JSON 契约。请重新输出完整合法对象，检查步骤及依赖。"));
            return attempt(corrected, content, tier, onUsage, retry + 1, context);
        });
    }

    Decision decode(String text, String content) {
        return decode(text, content, Map.of());
    }

    private Decision decode(String text, String content, Map<String, Object> context) {
        try {
            String json = text.trim();
            if (json.startsWith("```") && json.endsWith("```")) json = json.substring(json.indexOf('\n') + 1, json.length() - 3).trim();
            Decision d = mapper.readValue(json, Decision.class);
            if (d == null || d.kind() == null || !Set.of("single", "composite", "chitchat", "unsafe").contains(d.kind())
                    || d.confidence() == null || !Double.isFinite(d.confidence()) || d.confidence() < 0 || d.confidence() > 1)
                throw new IllegalArgumentException("无效意图");
            if (d.needsClarification()) {
                if (d.clarifyingQuestion() == null || d.clarifyingQuestion().isBlank() || d.clarifyingQuestion().length() > 500)
                    throw new IllegalArgumentException("缺少澄清问题");
                return new Decision(d.kind(), d.expert(), d.confidence(), true, d.clarifyingQuestion(), null);
            }
            Plan plan = switch (d.kind()) {
                case "single" -> { if (d.expert() == null) throw new IllegalArgumentException("缺少专家"); yield single(content, d.expert(), context); }
                case "composite" -> PlanValidator.validate(d.plan());
                default -> null;
            };
            return new Decision(d.kind(), d.expert(), d.confidence(), false, null, plan);
        } catch (IOException ex) { throw new IllegalArgumentException("无效规划 JSON", ex); }
    }

    private Plan single(String content, ExpertType expert, Map<String, Object> context) {
        PlanStep probe = new PlanStep("s1", experts.label(expert), expert, Map.of("request", content), List.of());
        String title = experts.label(expert) + (experts.matching(probe, context) || experts.diagnosing(probe, context)
                || expert == ExpertType.COACH || expert == ExpertType.CAREER_DISCOVERY || experts.interviewing(probe)
                || expert == ExpertType.JOB && jobs != null ? "" : "（占位）");
        return PlanValidator.validate(new Plan(content, List.of(new PlanStep("s1", title,
                expert, Map.of("request", content), List.of()))));
    }
}
