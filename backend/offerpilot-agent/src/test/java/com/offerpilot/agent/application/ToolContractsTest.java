package com.offerpilot.agent.application;
import com.offerpilot.agent.tools.ToolContracts;
import com.offerpilot.agent.domain.*;
import com.offerpilot.agent.event.AgentEvent;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class ToolContractsTest {
    @Test void analysisApprovalIsAuditedAndCancelledInsteadOfDetached() {
        var store=org.mockito.Mockito.mock(ApprovalStore.class);
        var service=new ApprovalService(org.mockito.Mockito.mock(com.offerpilot.conversation.application.ConversationService.class),store);
        var ticket=service.request("owner","run","generateAnalysis",Map.of("expert","JOB","inputs",Map.of("action","match"),"resources",Map.of("jobId","job_fixture")));
        org.mockito.Mockito.verify(store).insert(org.mockito.ArgumentMatchers.eq(ticket.id()),org.mockito.ArgumentMatchers.eq("owner"),org.mockito.ArgumentMatchers.eq("run"),org.mockito.ArgumentMatchers.eq("generateAnalysis"),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.any());
        var wait=ticket.await().subscribe();wait.dispose();
        org.mockito.Mockito.verify(store).timeout(ticket.id());
        assertThatThrownBy(() -> service.resolve("owner","run",ticket.id(),new ApprovalService.Decision("APPROVE",null,null)))
            .hasMessageContaining("已处理或已超时");
    }
    @Test void everyRegisteredToolHasAnExplicitGradeAndClosedSchema() {
        assertThat(ToolContracts.all()).hasSizeGreaterThan(30);
        for(var c:new com.offerpilot.agent.experts.ExpertRegistry().registeredTools()) {
            assertThat(c.access()).isNotNull();
            assertThat(c.schema().path("type").asText()).isEqualTo("object");
            assertThat(c.schema().path("additionalProperties").asBoolean(true)).isFalse();
            if(c.expert()!=null) {
                var step=new PlanStep("s","test",c.expert(),Map.of("action",c.action()),List.of());
                boolean writing=BoardWriteTools.recognizes(step)||CareerWriteTools.recognizes(step)||InterviewWriteTools.recognizes(step)
                    ||CoachWriteTools.recognizes(step)||ResumePatchWriteTools.recognizes(step)||StarStoryWriteTools.recognizes(step);
                assertThat(c.access()==AgentEvent.ToolAccess.WRITE).as(c.action()).isEqualTo(writing || Set.of("match","diagnose","diagnosis","optimize").contains(c.action()));
            }
        }
        for(String a:DetachedApprovals.AGENT_ACTIONS) assertThat(ToolContracts.all()).anyMatch(c -> c.action().equals(a) && c.access()==AgentEvent.ToolAccess.WRITE);
        for(String a:BoardWriteTools.ACTIONS) assertThat(ToolContracts.all()).anyMatch(c -> c.action().equals(a));
    }
    @Test void rejectsUnknownToolsMissingFieldsWrongTypesAndExtraFieldsWithoutReflectingValues() {
        for(var args:List.<Map<String,Object>>of(Map.of("action","deleteEverything"),Map.of("action","createApplication"),
                Map.of("action","createApplication","jobId",123),Map.of("action","search","query",List.of("secret-value")),
                Map.of("action","search","credential","secret-value"))) {
            assertThatThrownBy(() -> ToolContracts.validate(new PlanStep("s","test",ExpertType.JOB,args,List.of())))
                .hasMessageContaining("TOOL_ARGUMENT_INVALID").hasMessageNotContaining("secret-value");
        }
        ToolContracts.validate(new PlanStep("s","test",ExpertType.JOB,Map.of("action","createApplication","jobId","job_fixture"),List.of()));
        assertThatThrownBy(() -> ToolContracts.validate(new PlanStep("s","test",ExpertType.INTERVIEWER,Map.of("action","saveStarStory","sessionId","iv","questionIndex",8),List.of())))
            .hasMessageContaining("range");
    }
}
