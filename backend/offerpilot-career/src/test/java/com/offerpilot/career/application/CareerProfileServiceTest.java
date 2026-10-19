package com.offerpilot.career.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.career.web.CareerProfileController.ProfileRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CareerProfileServiceTest {
    @Test void composesConfirmedAssessmentAndPersistsAllPanelsInOneWrite() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var rs = mock(ResultSet.class);
        when(rs.getString("target")).thenReturn("工程师");
        when(rs.getString("city")).thenReturn("杭州");
        when(rs.getString("skills")).thenReturn("Java");
        when(rs.getString("interests")).thenReturn("[\"技术研发\"]");
        when(rs.getString("completed")).thenReturn("[]");
        when(rs.getString("major")).thenReturn("计算机");
        when(rs.getString("courses")).thenReturn("算法\n数据库");
        when(rs.getString("values_text")).thenReturn("成长\n平衡");
        when(rs.getString("confirmed")).thenReturn("""
                {"riasec":{"R":40,"I":90,"A":10,"S":20,"E":75,"C":30},"code":"IER",
                "traits":[{"name":"好奇","confidence":0.8,"evidence":"喜欢研究原理"}],
                "constraints":{"city":"杭州","salary":"","overtime":"不加班"}}
                """);
        when(rs.getTimestamp("updated_at")).thenReturn(Timestamp.from(Instant.EPOCH));
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<CareerProfileService.Profile>>any(), eq("alice")))
                .thenAnswer(call -> List.of(call.<RowMapper<CareerProfileService.Profile>>getArgument(1).mapRow(rs, 0)));
        var service = new CareerProfileService(jdbc, new ObjectMapper());
        var result = service.save("alice", "工程师", "杭州", "Java", List.of("技术研发"), "计算机", "算法\n数据库", "成长\n平衡");
        assertThat(result.major()).isEqualTo("计算机");
        assertThat(result.courses()).isEqualTo("算法\n数据库");
        assertThat(result.valuesText()).isEqualTo("成长\n平衡");
        assertThat(result.assessment().code()).isEqualTo("IER");
        assertThat(result.assessment().traits().get(0).evidence()).isEqualTo("喜欢研究原理");
        assertThat(result.assessment().constraints().overtime()).isEqualTo("不加班");
        verify(jdbc).update(contains("major=coalesce(?,career_profiles.major)"), eq("alice"), eq("工程师"), eq("杭州"),
                eq("Java"), eq("[\"技术研发\"]"), eq("计算机"), eq("算法\n数据库"), eq("成长\n平衡"),
                eq("计算机"), eq("算法\n数据库"), eq("成长\n平衡"));
    }
    @Test void newFieldsAreOptionalForLegacyClientsButBounded() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(new ProfileRequest("", "", "", List.of(), null, null, null))).isEmpty();
            assertThat(validator.validate(new ProfileRequest("", "", "", List.of(), "x".repeat(101), "x".repeat(3001), "x".repeat(1001))))
                    .extracting(v -> v.getPropertyPath().toString()).containsExactlyInAnyOrder("major", "courses", "valuesText");
        }
    }
}
