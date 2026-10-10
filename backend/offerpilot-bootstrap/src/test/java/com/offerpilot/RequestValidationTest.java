package com.offerpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.auth.application.PasswordLoginService;
import com.offerpilot.auth.application.StaffUserAdminService;
import com.offerpilot.auth.web.LoginController;
import com.offerpilot.auth.web.StaffUserController;
import com.offerpilot.board.application.ApplicationService;
import com.offerpilot.board.web.ApplicationController;
import com.offerpilot.common.error.GlobalExceptionHandler;
import com.offerpilot.llm.ModelTier;
import com.offerpilot.resume.application.ResumeModelSettings;
import com.offerpilot.resume.web.ResumeModelSettingsController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real MVC binding and exception advice, without a database or Docker. */
class RequestValidationTest {
    private final PasswordLoginService logins = mock(PasswordLoginService.class);
    private final StaffUserAdminService admin = mock(StaffUserAdminService.class);
    private final ApplicationService applications = mock(ApplicationService.class);
    private final ResumeModelSettings settings = mock(ResumeModelSettings.class);
    private final CurrentUser users = mock(CurrentUser.class);
    private final com.offerpilot.interview.application.InterviewService interviews = mock(com.offerpilot.interview.application.InterviewService.class);
    private final ObjectMapper json = new ObjectMapper();
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new LoginController(logins), new StaffUserController(admin),
                        new ApplicationController(applications, users), new ResumeModelSettingsController(settings, users),
                        new com.offerpilot.interview.web.InterviewController(interviews, mock(com.offerpilot.interview.application.InterviewRecapExporter.class), users))
                .setValidator(validator).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void closeValidator() { validator.close(); }

    @Test
    void rewriteAcceptsOnlyAnOptionalTextJobIdAlongsideExistingFields() throws Exception {
        for (String extra : List.of("\"jobId\":7", "\"jobId\":{}", "\"unknown\":\"job\"")) {
            var body=json.readTree("{\"requestId\":\"rewrite-request-123\",\"jobDescription\":\"Java\","+extra+"}");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> com.offerpilot.resume.application.ResumeOptimizationService.input(body))
                .isInstanceOf(com.offerpilot.common.error.BusinessException.class);
        }
        assertThat(com.offerpilot.resume.application.ResumeOptimizationService.input(json.readTree("{\"requestId\":\"rewrite-request-123\",\"jobDescription\":\"Java\",\"jobId\":\"job_1\"}")))
            .isEqualTo(new com.offerpilot.resume.application.ResumeOptimizationService.Input("rewrite-request-123","Java","job_1"));
    }

    static Stream<Arguments> invalidBodies() {
        Stream<Arguments> auth = Stream.of("login", "register", "staff/login").flatMap(endpoint ->
                Stream.of("{}", "{\"email\":\"person@example.com\",\"password\":\" \"}")
                        .map(body -> Arguments.of(HttpMethod.POST, "/api/v1/auth/" + endpoint, body)));
        Stream<Arguments> staff = Stream.of("{}", "{\"status\":\"UNKNOWN\"}")
                .map(body -> Arguments.of(HttpMethod.PATCH, "/api/v1/staff/users/usr_test/status", body));
        Stream<Arguments> board = Stream.of("{\"status\":\"UNKNOWN\"}",
                        "{\"note\":\"" + "😀".repeat(2001) + "\"}")
                .map(body -> Arguments.of(HttpMethod.PATCH, "/api/v1/applications/app_test", body));
        Stream<Arguments> models = Stream.of("{}",
                        "{\"textTier\":\"unknown\",\"visionTier\":\"VISION_ALT\"}",
                        "{\"textTier\":\"FLASH\",\"visionTier\":\"VISION\"}")
                .map(body -> Arguments.of(HttpMethod.PUT, "/api/v1/resume-model-settings", body));
        Stream<Arguments> interview = Stream.of("", " ", "a".repeat(81)).map(job -> Arguments.of(HttpMethod.POST,"/api/v1/interviews",
            "{\"role\":\"Java\",\"kind\":\"项目与经历\",\"jobId\":\""+job+"\"}"));
        return Stream.of(auth, staff, board, models, interview).flatMap(stream -> stream);
    }

    @ParameterizedTest
    @MethodSource("invalidBodies")
    void invalidBodyReturnsValidationFailedBeforeCallingServices(HttpMethod method, String path, String body) throws Exception {
        var result = mvc.perform(request(method, path).contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.errors").isNotEmpty()).andReturn();
        assertThat(result.getResolvedException()).isInstanceOf(MethodArgumentNotValidException.class);
        verifyNoInteractions(logins, admin, applications, settings, interviews, users);
    }

    @Test
    void acceptsUnicodeNoteBoundaryAndEmptyNoteWithoutRequiringStatus() throws Exception {
        when(users.require(null)).thenReturn("owner");
        for (String note : List.of("😀".repeat(2000), "a\nb", "")) {
            mvc.perform(patch("/api/v1/applications/app_test").contentType("application/json")
                    .content(json.writeValueAsString(Map.of("note", note)))).andExpect(status().isOk());
            verify(applications).updateWithPatch("owner", "app_test",
                    new ApplicationService.Patch(null, note, null, null), "USER", null);
        }
        for (Map<String, String> body : List.of(
                Map.of("nextAction", "😀".repeat(1000), "nextActionAt", "2026-10-11"),
                Map.of("nextAction", "", "nextActionAt", ""))) {
            mvc.perform(patch("/api/v1/applications/app_test").contentType("application/json")
                    .content(json.writeValueAsString(body))).andExpect(status().isOk());
            verify(applications).updateWithPatch("owner", "app_test",
                    new ApplicationService.Patch(null, null, body.get("nextAction"), body.get("nextActionAt")),
                    "USER", null);
        }

        clearInvocations(applications, users);
        for (Map<String, String> body : List.of(
                Map.of("nextAction", "😀".repeat(1001)),
                Map.of("nextActionAt", "2026-10-111"))) {
            var result = mvc.perform(patch("/api/v1/applications/app_test").contentType("application/json")
                            .content(json.writeValueAsString(body)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(10001))
                    .andExpect(jsonPath("$.errors").isNotEmpty()).andReturn();
            assertThat(result.getResolvedException()).isInstanceOf(MethodArgumentNotValidException.class);
            verifyNoInteractions(applications, users);
        }
    }

    @Test
    void acceptsAllModelTierNamesForServiceAvailabilityChecks() throws Exception {
        when(users.require(null)).thenReturn("owner");
        for (ModelTier tier : ModelTier.values()) {
            mvc.perform(put("/api/v1/resume-model-settings").contentType("application/json")
                    .content(json.writeValueAsString(Map.of("textTier", tier.name(), "visionTier", tier.name()))))
                    .andExpect(status().isOk());
            verify(settings).save("owner", new ResumeModelSettings.Selection(tier.name(), tier.name()));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"page=abc", "size=abc", "hideGuests=abc", "page=2147483648"})
    void malformedStaffQueryReturnsValidationError(String query) throws Exception {
        mvc.perform(get("/api/v1/staff/users?" + query)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(10001));
        verifyNoInteractions(admin);
    }

}
