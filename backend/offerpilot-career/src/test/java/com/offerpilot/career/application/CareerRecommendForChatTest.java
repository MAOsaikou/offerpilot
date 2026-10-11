package com.offerpilot.career.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CareerRecommendForChatTest {
    final CareerProfileService profiles = mock(CareerProfileService.class);
    final OccupationRecommender occupations = mock(OccupationRecommender.class);
    final CareerRecommendForChat careers = new DefaultCareerRecommendForChat(profiles, occupations);

    @Test void mapsEveryChipWithStableTiesAndNoZeroScorePadding() {
        assertThat(InterestHollandMapping.derive(List.of("技术研发")).code()).isEqualTo("RI");
        assertThat(InterestHollandMapping.derive(List.of("产品策划")).code()).isEqualTo("ASE");
        assertThat(InterestHollandMapping.derive(List.of("数据分析")).code()).isEqualTo("IC");
        assertThat(InterestHollandMapping.derive(List.of("创意设计")).code()).isEqualTo("A");
        assertThat(InterestHollandMapping.derive(List.of("沟通协作")).code()).isEqualTo("SE");
        assertThat(InterestHollandMapping.derive(List.of("研究探索")).code()).isEqualTo("RI");
        assertThat(InterestHollandMapping.derive(List.of("技术研发", "数据分析")).code()).isEqualTo("IRC");
        assertThat(InterestHollandMapping.derive(List.of("数据分析", "技术研发", "技术研发", "未知")))
                .isEqualTo(InterestHollandMapping.derive(List.of("技术研发", "数据分析")));
        assertThat(InterestHollandMapping.derive(List.of("未知")).code()).isEmpty();
    }

    @Test void recommendationUsesOwnerProfileExistingRankingAndDeterministicReasons() {
        when(profiles.get("alice")).thenReturn(profile(List.of("技术研发", "数据分析", "未知")));
        when(occupations.rankHybrid(eq(new RiasecProfile(1, 2, 0, 0, 0, 1)), anyString(), eq(8))).thenReturn(List.of(
                new OccupationRecommender.Ranked("occ1", "工程师", "技术", "IRC", 1, List.of(new OccupationRecommender.Citation("occ1", "工程师", "开发 Java 服务")), "文本匹配"),
                new OccupationRecommender.Ranked("occ2", "分析师", "数据", "ICR", .75)));
        var result = careers.recommend("alice");
        assertThat(result.guidance()).isNull();
        assertThat(result.currentProfile()).isEqualTo(new CareerRecommendForChat.CurrentProfile("旧目标", "上海"));
        assertThat(result.payload().hollandCode()).isEqualTo("IRC");
        assertThat(result.payload().interestsUsed()).containsExactlyInAnyOrder("技术研发", "数据分析");
        assertThat(result.payload().recommendations()).extracting(CareerRecommendForChat.Recommendation::name)
                .containsExactly("工程师", "分析师");
        assertThat(result.payload().recommendations().get(0).similarity()).isEqualTo(1);
        assertThat(result.payload().recommendations().get(0).citations()).singleElement()
                .satisfies(citation -> assertThat(citation.excerpt()).isEqualTo("开发 Java 服务"));
        verify(occupations).rankHybrid(new RiasecProfile(1, 2, 0, 0, 0, 1), "技术研发 数据分析 Java 旧目标", 8);
        assertThat(result.payload().recommendations().get(1).similarity()).isEqualTo(.75);
        assertThat(result.payload().recommendations()).allSatisfy(item -> {
            assertThat(item.reasons()).hasSize(3);
            assertThat(item.reasons().get(0)).contains("六维 RIASEC", "r=", "Pearson");
        });
        assertThat(careers.recommend("alice")).isEqualTo(result);
        verify(profiles, never()).saveTarget(anyString(), anyString());
    }

    @Test void absentOrUnknownInterestsGiveGuidanceWithoutRankingOrWriting() {
        for (var interests : List.of(List.<String>of(), List.of("未知兴趣"))) {
            when(profiles.get("alice")).thenReturn(profile(interests));
            var result = careers.recommend("alice");
            assertThat(result.payload()).isNull();
            assertThat(result.guidance()).contains("探索页勾选兴趣", "对话里说明").doesNotContain("占位");
        }
        verifyNoInteractions(occupations);
    }


    static CareerProfileService.Profile profile(List<String> interests) {
        return new CareerProfileService.Profile("旧目标", "上海", "Java", interests, List.of(), null);
    }
}
