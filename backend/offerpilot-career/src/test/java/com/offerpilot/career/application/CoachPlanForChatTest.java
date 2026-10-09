package com.offerpilot.career.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CoachPlanForChatTest {
    final CareerProfileService profiles = mock(CareerProfileService.class);
    final OccupationRecommender occupations = mock(OccupationRecommender.class);
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final CoachResumeSkills resumes = mock(CoachResumeSkills.class);
    final ObjectMapper json = new ObjectMapper();
    DefaultCoachPlanForChat coach() {
        var beans = new StaticListableBeanFactory(); beans.addBean("skills", resumes);
        return new DefaultCoachPlanForChat(profiles, occupations, jdbc, json, beans.getBeanProvider(CoachResumeSkills.class));
    }
    @Test void deterministicMathUsesOnlyExplicitSkillTokensAndOrdersTopGaps() throws Exception {
        var required = json.readTree("""
            [{"name":"Java","level":4},{"name":"SQL","level":5},{"name":"沟通","level":"必备"},
             {"name":" java ","level":2},{"name":"Go","required":1}]
            """);
        var result = DefaultCoachPlanForChat.gaps(required, "  JAVA、Go\nJavaScript", List.of(" sql "));
        assertThat(result).extracting(CoachPlanForChat.Skill::name).containsExactly("沟通", "Java", "SQL", "Go");
        assertThat(result).extracting(CoachPlanForChat.Skill::gap).containsExactly(4,2,2,0);
        assertThat(result).extracting(CoachPlanForChat.Skill::current).containsExactly(0,2,3,2);
        assertThat(result).extracting(CoachPlanForChat.Skill::learnFirst).containsExactly(true,true,false,false);
        assertThat(result.get(2).evidence()).containsExactly("已保存简历技能或项目技术列出：SQL");
        assertThat(DefaultCoachPlanForChat.gaps(required, "JavaScript", List.of()).stream()
                .filter(s -> s.name().equals("Java")).findFirst().orElseThrow().current()).isZero();
        assertThat(DefaultCoachPlanForChat.gaps(required, "  JAVA、Go\nJavaScript", List.of(" sql "))).isEqualTo(result);
    }
    @Test void noTargetGuidesWithoutReadingOrWritingAndUnknownTargetIsHonest() {
        when(profiles.get("alice")).thenReturn(profile("", ""));
        var result = coach().plan("alice", null, null, "req");
        assertThat(result.summary()).contains("设为目标").doesNotContain("占位");
        assertThat(result.skillGap()).isNull(); assertThat(result.roadmap()).isNull();
        verifyNoInteractions(occupations,jdbc,resumes);
        when(profiles.get("alice")).thenReturn(profile("自由填写目标", ""));
        when(occupations.find(null,"自由填写目标")).thenThrow(new com.offerpilot.common.error.BusinessException(
                com.offerpilot.common.error.ErrorCode.VALIDATION_FAILED));
        assertThat(coach().plan("alice",null,null,"req").summary()).contains("尚未关联职业库");
    }
    @Test void savedTargetProducesBothPayloadsWithCatalogTitlesCompletionsAndNoFakeCitations() {
        when(profiles.get("alice")).thenReturn(profile("工程师", "Java"));
        when(occupations.find(null,"工程师")).thenReturn(new OccupationRecommender.Ranked("occ", "工程师", "技术", "IRC", 0));
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("occ"))).thenReturn("[{\"name\":\"Java\"},{\"name\":\"SQL\"}]");
        when(resumes.read("alice","rs","rv")).thenReturn(new CoachResumeSkills.Snapshot(true,List.of("SQL")));
        var coach = coach(); var result = coach.plan("alice","rs","rv","req");
        assertThat(result.skillGap().skills()).extracting(CoachPlanForChat.Skill::current).containsExactly(2,3);
        assertThat(result.skillGap().citations()).isEmpty();
        assertThat(result.skillGap().target().id()).isEqualTo("occ");
        assertThat(result.roadmap().weeks()).hasSize(4);
        var items = result.roadmap().weeks().stream().flatMap(w -> w.items().stream()).toList();
        assertThat(items).extracting(CoachPlanForChat.Item::title).containsExactlyInAnyOrderElementsOf(GrowthTaskCatalog.TITLES);
        assertThat(items.get(0).done()).isTrue();
        assertThat(result.summary()).contains("结构化简历", "未写入");
        assertThat(coach.plan("alice","rs","rv","req")).isEqualTo(result);
        when(resumes.read("alice",null,null)).thenReturn(new CoachResumeSkills.Snapshot(false,List.of()));
        assertThat(coach.plan("alice",null,null,"req").summary()).contains("仅参考档案技能");
        when(jdbc.queryForObject(anyString(), eq(String.class), eq("occ"))).thenReturn("[]");
        assertThat(coach.plan("alice",null,null,"req").summary()).contains("暂不能判断具体差距");
        verify(profiles,never()).mergeTasks(anyString(),anyList());
    }
    static CareerProfileService.Profile profile(String target,String skills) {
        return new CareerProfileService.Profile(target,"上海",skills,List.of(),List.of(GrowthTaskCatalog.TITLES.get(0)),null);
    }
}
