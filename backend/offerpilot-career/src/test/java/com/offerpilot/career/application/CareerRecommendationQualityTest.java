package com.offerpilot.career.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;
import java.sql.ResultSet;
import java.util.*;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Offline card quality gate: count, source grounding, user evidence and explicit missing values. */
class CareerRecommendationQualityTest {
    @Test void eightCardsHaveTwoSavedUserFactsAndCatalogFieldsWithoutInventingMissingData() throws Exception {
        var jdbc=mock(JdbcTemplate.class);
        var profiles=mock(CareerProfileService.class);
        var occupations=mock(OccupationRecommender.class);
        var service=new DefaultCareerRecommendForChat(profiles,occupations);
        ReflectionTestUtils.setField(service,"details",new CareerCardDetails(jdbc,new ObjectMapper()));
        var profile=new CareerProfileService.Profile("后端工程师","杭州","Java",List.of("技术研发"),List.of(),null);
        when(profiles.get("alice")).thenReturn(profile);
        when(occupations.rankHybrid(any(RiasecProfile.class),anyString(),eq(8))).thenReturn(IntStream.range(0,8)
                .mapToObj(i -> new OccupationRecommender.Ranked("occ"+i,"职业"+i,"技术","IRC",.9-i*.01)).toList());
        when(jdbc.query(eq("SELECT * FROM occupations WHERE id=?"),org.mockito.ArgumentMatchers.<RowMapper<CareerCardDetails.Details>>any(),any()))
                .thenAnswer(call -> {
                    ResultSet rs=mock(ResultSet.class);
                    when(rs.getString("core_skills")).thenReturn("[{\"name\":\"Java\"}]");
                    when(rs.getString("growth_path")).thenReturn("初级到资深");
                    when(rs.getString("source")).thenReturn("目录测试条目");
                    RowMapper<CareerCardDetails.Details> mapper=call.getArgument(1);
                    return List.of(mapper.mapRow(rs,0));
                });
        var cards=service.recommend("alice").payload().recommendations();
        assertThat(cards).hasSizeBetween(5,8).extracting(CareerRecommendForChat.Recommendation::occupationId).doesNotHaveDuplicates();
        assertThat(cards).allSatisfy(card -> {
            assertThat(card.similarity()).isBetween(0.0,1.0);
            var d=card.details();
            assertThat(d.evidence()).hasSize(2).allSatisfy(e -> {
                assertThat(List.of(profile.target(),profile.city())).contains(e.value());
                assertThat(e.source()).isEqualTo("career/profile"); assertThat(e.field()).isNotBlank();
            });
            assertThat(d.sourceOccupationId()).isEqualTo(card.occupationId());
            assertThat(d.source()).isEqualTo("目录测试条目");
            assertThat(d.coreSkills()).containsExactly("Java");
            assertThat(d.growthPath()).isEqualTo("初级到资深");
            assertThat(d.typicalTitles()).containsExactly("未提供");
            assertThat(d.companies()).containsExactly("未提供");
            assertThat(d.salaryRange()).isEqualTo("未提供");assertThat(d.concerns()).isEqualTo("未提供");
        });
    }
}
