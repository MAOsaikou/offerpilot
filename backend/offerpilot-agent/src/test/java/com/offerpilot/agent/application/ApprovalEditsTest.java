package com.offerpilot.agent.application;

import com.offerpilot.career.application.CareerRecommendForChat;
import com.offerpilot.career.application.GrowthTaskCatalog;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.conversation.application.ConversationService;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApprovalEditsTest {
    final ApprovalStore store = mock(ApprovalStore.class);
    final CareerRecommendForChat careers = mock(CareerRecommendForChat.class);
    final ApprovalService service = new ApprovalService(mock(ConversationService.class), store, careers);
    record Edit(String action, Map<String, Object> original, Map<String, Object> edit, Map<String, Object> invalid) {}
    List<Edit> cases() {
        return List.of(
            new Edit("adoptGrowthPlan", Map.of("taskTitles", List.of(GrowthTaskCatalog.TITLES.get(0))), Map.of("taskTitles", List.of(GrowthTaskCatalog.TITLES.get(1))), Map.of("taskTitles", List.of("invented"))),
            new Edit("applyPatches", Map.of("resumeId","r", "resumeVersionId","v", "batchId","b", "accepted",List.of("p1")), Map.of("accepted",List.of(), "edited",Map.of("p1",Map.of("value","核对后的描述"))), Map.of("edited",Map.of("p2",Map.of("owner","bad")))),
            new Edit("saveStarStory", Map.of("sessionId","s", "questionIndex",0), Map.of("questionIndex",1), Map.of("questionIndex",1.5)),
            new Edit("startInterview", Map.of("role","开发", "kind","项目与经历"), Map.of("role","后端"), Map.of("kind","invalid")),
            new Edit("answerInterview", Map.of("sessionId","s", "expectedUpdatedAt","pinned", "answer","原答案", "nextQuestion",false), Map.of("answer","修改答案", "nextQuestion",true), Map.of("answer"," ")),
            new Edit("saveUserFact", Map.of("kind","GOAL", "source","EXPLICIT", "fact","我的目标岗位是后端工程师"), Map.of("fact","目标岗位是后端工程师"), Map.of("fact","我的目标岗位是产品经理")));
    }
    @Test void everyMeaningfulEditUsesToolValidationAndKeepsOriginalAuditAndResourceLocks() {
        when(store.claim(anyString(), eq("alice"), anyString(), anyMap(), isNull())).thenReturn(true);
        for (Edit edit : cases()) {
            var ticket = service.request("alice", "run", edit.action(), edit.original());
            assertThat(ApprovalService.editableFields(edit.action())).containsAll(edit.edit().keySet());
            verify(store).insert(eq(ticket.id()), eq("alice"), eq("run"), eq(edit.action()), eq(edit.original()), eq(ticket.expiresAt()));
            assertThatThrownBy(() -> service.resolve("alice", "run", ticket.id(), new ApprovalService.Decision("APPROVE", edit.invalid(), null))).isInstanceOf(BusinessException.class);
            assertThatThrownBy(() -> service.resolve("alice", "run", ticket.id(), new ApprovalService.Decision("APPROVE", Map.of("owner","attacker"), null))).isInstanceOf(BusinessException.class);
            var decision = service.resolve("alice", "run", ticket.id(), new ApprovalService.Decision("APPROVE", edit.edit(), null));
            assertThat(decision.args()).containsAllEntriesOf(edit.edit());
            assertThat(ticket.args()).isEqualTo(edit.original());
            verify(store).claim(ticket.id(), "alice", "APPROVED", decision.args(), null);
            assertThat(service.mergeForDecision(edit.action(), edit.original(), new ApprovalService.Decision("APPROVE", edit.edit(), null))).isEqualTo(decision.args());
        }
    }
    @Test void careerNamesResolveCanonicalCatalogSelectionAndDoNotAcceptIdsOrInventedCareers() {
        var original = Map.<String,Object>of("occupationId","original", "name","原目标");
        when(careers.occupation(null, "后端工程师")).thenReturn(new com.offerpilot.career.application.OccupationRecommender.Ranked("canonical", "后端工程师", "技术", "IRC", 0));
        assertThat(service.mergeForDecision("setCareerTarget", original, new ApprovalService.Decision("APPROVE", Map.of("name","后端工程师"), null)))
                .containsEntry("occupationId", "canonical").containsEntry("name", "后端工程师");
        // Catalog resolution remains owned by the career application boundary.
        when(careers.occupation(null, "未知职业")).thenThrow(new BusinessException(com.offerpilot.common.error.ErrorCode.VALIDATION_FAILED));
        assertThatThrownBy(() -> service.mergeForDecision("setCareerTarget", original, new ApprovalService.Decision("APPROVE", Map.of("name","未知职业"), null))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.mergeForDecision("setCareerTarget", original, new ApprovalService.Decision("APPROVE", Map.of("occupationId","foreign"), null))).isInstanceOf(BusinessException.class);
    }
    @Test void liveExpiryRecordsTimeoutAndSignalsRejectionWithoutClaimingOrExecuting() {
        var ticket = service.request("alice", "run", "startInterview", Map.of("role","开发", "kind","项目与经历"), Duration.ofMillis(15));
        var decision = ticket.await().block(Duration.ofSeconds(2));
        assertThat(decision.decision()).isEqualTo("TIMEOUT");
        verify(store).timeout(ticket.id());
        verify(store, never()).claim(anyString(), anyString(), anyString(), anyMap(), any());
        assertThatThrownBy(() -> service.resolve("alice", "run", ticket.id(), new ApprovalService.Decision("APPROVE", null, null))).isInstanceOf(BusinessException.class);
    }
}
