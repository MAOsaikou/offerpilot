package com.offerpilot.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.error.GlobalExceptionHandler;
import com.offerpilot.interview.application.InterviewRecapExporter;
import com.offerpilot.interview.application.InterviewService;
import com.offerpilot.interview.web.InterviewController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.Map;
import java.util.stream.Stream;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InterviewValidationTest {
    final InterviewService service = mock(InterviewService.class);
    final CurrentUser users = mock(CurrentUser.class);
    final ObjectMapper json = new ObjectMapper();
    LocalValidatorFactoryBean validator;
    MockMvc mvc;

    @BeforeEach
    void setup() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new InterviewController(service, new InterviewRecapExporter(service), users))
                .setValidator(validator).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void close() { validator.close(); }

    static Stream<Arguments> invalid() {
        return Stream.of(
                Arguments.of("POST", "", Map.of()),
                Arguments.of("POST", "", Map.of("role", " ", "kind", "项目与经历")),
                Arguments.of("POST", "", Map.of("role", "a".repeat(101), "kind", "项目与经历")),
                Arguments.of("POST", "", Map.of("role", "😀".repeat(101), "kind", "项目与经历")),
                Arguments.of("POST", "", Map.of("role", "工程师", "kind", "其他")),
                Arguments.of("POST", "", Map.of("role", "工程师")),
                Arguments.of("PATCH", "/iv_1", Map.of("questionIndex", -1)),
                Arguments.of("PATCH", "/iv_1", Map.of("questionIndex", 5)),
                Arguments.of("PUT", "/iv_1/answers/0", Map.of()),
                Arguments.of("PUT", "/iv_1/answers/0", Map.of("answerText", "a".repeat(12001))),
                Arguments.of("PUT", "/iv_1/answers/0", Map.of("answerText", "😀".repeat(12001))));
    }
    @ParameterizedTest @MethodSource("invalid")
    void rejectsInvalidBodiesBeforeCallingService(String method, String path, Map<String, ?> body) throws Exception {
        mvc.perform(request(org.springframework.http.HttpMethod.valueOf(method), "/api/v1/interviews" + path)
                .contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(10001));
        verifyNoInteractions(service, users);
    }

    @Test
    void unicodeBoundariesAndEmptyDraftPassValidation() throws Exception {
        when(users.require(null)).thenReturn("owner");
        for (String kind : new String[] {"项目与经历", "表达与结构", "职业动机"}) {
            mvc.perform(post("/api/v1/interviews").contentType("application/json")
                    .content(json.writeValueAsString(Map.of("role", "😀".repeat(100), "kind", kind))))
                    .andExpect(status().isOk());
            verify(service).create("owner", "😀".repeat(100), kind);
        }
        for (String answer : new String[] {"", "😀".repeat(12000), "多行\n回答"}) {
            mvc.perform(put("/api/v1/interviews/iv_1/answers/0").contentType("application/json")
                    .content(json.writeValueAsString(Map.of("answerText", answer))))
                    .andExpect(status().isOk());
            verify(service).saveAnswer("owner", "iv_1", 0, answer);
        }
    }
}
