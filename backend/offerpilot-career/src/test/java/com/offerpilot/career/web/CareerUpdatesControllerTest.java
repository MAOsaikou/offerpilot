package com.offerpilot.career.web;
import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.career.application.*;
import com.offerpilot.common.error.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class CareerUpdatesControllerTest {
    @Test void identityGatesBothReadsAndWritesAndClientCannotOverrideOwner() throws Exception {
        var users=mock(CurrentUser.class);var plans=mock(CareerTargetPlans.class);var suggestions=mock(ProfileSuggestions.class);
        when(users.require(null)).thenThrow(new BusinessException(ErrorCode.UNAUTHENTICATED));when(users.require("Bearer token")).thenReturn("trusted");
        var mvc=MockMvcBuilders.standaloneSetup(new CareerUpdatesController(users,plans,suggestions)).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/v1/career/target-plan")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/career/profile-suggestions")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/career/profile-suggestions/s/decision").contentType("application/json").content("{\"accept\":true,\"skills\":[]}")).andExpect(status().isUnauthorized());verifyNoInteractions(plans,suggestions);
        mvc.perform(get("/api/v1/career/target-plan").header("Authorization","Bearer token").param("userId","forged")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));verify(plans).get("trusted");
        mvc.perform(post("/api/v1/career/target-plan/confirm").header("Authorization","Bearer token").contentType("application/json").content("{\"revision\":\"r1\"}")).andExpect(status().isOk());verify(plans).confirm("trusted","r1");
        mvc.perform(post("/api/v1/career/profile-suggestions/s/decision").header("Authorization","Bearer token").contentType("application/json").content("{\"accept\":true,\"skills\":[\"Java\"]}")).andExpect(status().isOk());verify(suggestions).decide("trusted","s",true,java.util.List.of("Java"));
    }
}
