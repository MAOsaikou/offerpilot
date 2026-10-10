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
    void recapDownloadsUtf8MarkdownAndWrongAcceptIs406() throws Exception {
        when(users.require(null)).thenReturn("owner");
        when(service.get("owner", "iv_1")).thenReturn(new InterviewService.Session("iv_1", "后端", "项目与经历", 0,
                true, "now", "now", java.util.List.of(new InterviewService.Answer(0, "问题", "当时负责，我实现，最终完成"))));
        for (String accept : new String[] {"text/markdown", "*/*"}) {
            mvc.perform(get("/api/v1/interviews/iv_1/recap.md").accept(accept))
                    .andExpect(status().isOk()).andExpect(content().contentType("text/markdown;charset=UTF-8"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("面试复盘包")))
                    .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("filename*=UTF-8''")));
        }
        mvc.perform(get("/api/v1/interviews/iv_1/recap.md").accept("application/json"))
                .andExpect(status().isNotAcceptable()).andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Accept")));
    }

    @Test
    void unicodeBoundariesAndEmptyDraftPassValidation() throws Exception {
        when(users.require(null)).thenReturn("owner");
        for (String kind : new String[] {"项目与经历", "表达与结构", "职业动机"}) {
            mvc.perform(post("/api/v1/interviews").contentType("application/json")
                    .content(json.writeValueAsString(Map.of("role", "😀".repeat(100), "kind", kind))))
                    .andExpect(status().isOk());
            verify(service).create("owner", "😀".repeat(100), kind, null);
        }
        for (String answer : new String[] {"", "😀".repeat(12000), "多行\n回答", "当时在校园预约系统项目中，背景是高峰期接口超时。我负责预约接口稳定性，目标是把 P95 压到 200ms 内。"
                + "我先排查慢查询，再设计缓存与限流，我实现了批量写入并做压测验证。最终 P95 从 800ms 降到 180ms，结果是预约成功率提升，也完成了答辩演示。"}) {
            mvc.perform(put("/api/v1/interviews/iv_1/answers/0").contentType("application/json")
                    .content(json.writeValueAsString(Map.of("answerText", answer))))
                    .andExpect(status().isOk());
            verify(service).saveAnswer("owner", "iv_1", 0, answer);
        }
    }
}
