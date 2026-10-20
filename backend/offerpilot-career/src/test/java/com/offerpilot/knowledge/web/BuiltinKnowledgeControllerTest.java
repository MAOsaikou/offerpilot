package com.offerpilot.knowledge.web;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.error.*;
import com.offerpilot.knowledge.application.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BuiltinKnowledgeControllerTest {
    @Test void allReadRoutesRequireIdentityAndUseSharedEnvelope() throws Exception {
        var users=mock(CurrentUser.class);var repository=mock(BuiltinKnowledgeRepository.class);var search=mock(BuiltinKnowledgeSearch.class);
        var mvc=MockMvcBuilders.standaloneSetup(new BuiltinKnowledgeController(users,repository,search)).setControllerAdvice(new GlobalExceptionHandler()).build();
        when(users.require(null)).thenThrow(new BusinessException(ErrorCode.UNAUTHENTICATED));
        for(String path:List.of("/facets","/occupations","/occupations/occ_backend_dev","/entries","/entries/kbq-01","/search?q=护理"))
            mvc.perform(get("/api/v1/knowledge/builtin"+path)).andExpect(status().isUnauthorized());
        verifyNoInteractions(repository,search);
        when(users.require("Bearer verified")).thenReturn("owner");
        when(repository.entries(any())).thenReturn(new BuiltinKnowledgeRepository.Page<>(List.of(),576,1,20));
        mvc.perform(get("/api/v1/knowledge/builtin/entries").header("Authorization","Bearer verified").param("kind","QUESTION").param("industry","教育").param("role","教师").param("questionType","CASE").param("difficulty","HARD"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(576));
        verify(repository).entries(new BuiltinKnowledgeRepository.Filters("QUESTION","","教育","教师","CASE","HARD",1,20));
        mvc.perform(get("/api/v1/knowledge/builtin/entries").header("Authorization","Bearer verified").param("page","x"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(10001));
    }
}
