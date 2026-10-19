package com.offerpilot.career.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.career.application.CareerAssessmentInference;
import com.offerpilot.career.application.CareerAssessmentService;
import com.offerpilot.career.application.CareerProfileService;
import com.offerpilot.common.error.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CareerProfileControllerTest {
    private final CareerProfileService profiles = mock(CareerProfileService.class);
    private final CurrentUser users = mock(CurrentUser.class);
    private final ObjectMapper json = new ObjectMapper();
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;

    @BeforeEach void setup() {
        validator = new LocalValidatorFactoryBean(); validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new CareerProfileController(profiles, users))
                .setValidator(validator).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void close() { validator.close(); }

    @Test void loadsComposedAssessmentAndSavesThreePanelsForTheAuthenticatedOwner() throws Exception {
        when(users.require("Bearer fixture")).thenReturn("alice");
        var confirmed = new CareerAssessmentService.Confirmed(Map.of("R", 30, "I", 90, "A", 20, "S", 40, "E", 80, "C", 10), "IES",
                List.of(new CareerAssessmentInference.Trait("好奇", 0.8, "研究原理")), new CareerAssessmentInference.Constraints("成都", "", ""));
        var result = new CareerProfileService.Profile("工程师", "成都", "SQL", List.of(), List.of(), null,
                "统计学", "概率论\n数据挖掘", "成长\n平衡", confirmed);
        when(profiles.load("alice")).thenReturn(result);
        mvc.perform(get("/api/v1/career/profile").header("Authorization", "Bearer fixture"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.assessment.code").value("IES"))
                .andExpect(jsonPath("$.data.assessment.traits[0].evidence").value("研究原理"));
        when(profiles.save("alice", "工程师", "成都", "SQL", List.of(), "统计学", "概率论\n数据挖掘", "成长\n平衡")).thenReturn(result);
        mvc.perform(put("/api/v1/career/profile").header("Authorization", "Bearer fixture").contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("target", "工程师", "city", "成都", "skills", "SQL", "interests", List.of(),
                        "major", "统计学", "courses", "概率论\n数据挖掘", "valuesText", "成长\n平衡"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.major").value("统计学"));
        verify(profiles).save("alice", "工程师", "成都", "SQL", List.of(), "统计学", "概率论\n数据挖掘", "成长\n平衡");
    }

    @Test void rejectsOversizeAndCoercedNewFieldsBeforeAnyPersistence() throws Exception {
        for (var invalid : List.of(Map.of("major", "长".repeat(101)), Map.of("courses", "长".repeat(3001)),
                Map.of("valuesText", "长".repeat(1001)), Map.of("major", 42), Map.of("courses", true), Map.of("valuesText", List.of("成长")))) {
            var body = new java.util.HashMap<String, Object>(Map.of("target", "", "city", "", "skills", "", "interests", List.of()));
            body.putAll(invalid);
            mvc.perform(put("/api/v1/career/profile").contentType("application/json").content(json.writeValueAsBytes(body)))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(profiles, users);
    }
}
