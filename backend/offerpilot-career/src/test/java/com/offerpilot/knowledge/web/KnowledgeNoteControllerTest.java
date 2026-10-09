package com.offerpilot.knowledge.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.error.GlobalExceptionHandler;
import com.offerpilot.knowledge.application.KnowledgeNoteSearch;
import com.offerpilot.knowledge.application.KnowledgeNoteService;
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

class KnowledgeNoteControllerTest {
    private final KnowledgeNoteService notes = mock(KnowledgeNoteService.class);
    private final KnowledgeNoteSearch search = mock(KnowledgeNoteSearch.class);
    private final CurrentUser users = mock(CurrentUser.class);
    private final ObjectMapper json = new ObjectMapper();
    private LocalValidatorFactoryBean validator;
    private MockMvc mvc;
    @BeforeEach void setup() {
        validator = new LocalValidatorFactoryBean(); validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new KnowledgeNoteController(users, notes, search))
                .setValidator(validator).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    @AfterEach void close() { validator.close(); }
    @Test void boundsCategoriesNullsAndWhitespaceFailBeforePersistence() throws Exception {
        for (Object body : List.of(Map.of(), Map.of("title", " ", "content", "x", "category", "面试复盘"),
                Map.of("title", "x", "content", " ", "category", "面试复盘"),
                Map.of("title", "x".repeat(121), "content", "x", "category", "面试复盘"),
                Map.of("title", "😀".repeat(121), "content", "x", "category", "面试复盘"),
                Map.of("title", "x", "content", "😀".repeat(30001), "category", "面试复盘"),
                Map.of("title", "x", "content", "x", "category", "未知"))) {
            for (var req : List.of(post("/api/v1/knowledge/notes"), put("/api/v1/knowledge/notes/note")))
                mvc.perform(req.contentType("application/json").content(json.writeValueAsBytes(body)))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(10001));
        }
        verifyNoInteractions(notes, search, users);
    }
    @Test void importValidatesNestedItemsAndBatchBounds() throws Exception {
        for (Object body : List.of(Map.of(), Map.of("notes", List.of()), Map.of("notes", List.of(Map.of("sourceId", "a", "note", Map.of()))),
                Map.of("notes", java.util.Collections.nCopies(51, Map.of("sourceId", "a", "note", Map.of("title", "x", "content", "x", "category", "面试复盘"))))))
            mvc.perform(post("/api/v1/knowledge/notes/import").contentType("application/json").content(json.writeValueAsBytes(body)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(10001));
        verifyNoInteractions(notes, users);
    }
    @Test void malformedQueryReturnsValidationErrorBeforeCallingServices() throws Exception {
        for (String path : List.of("?page=abc", "?size=2147483648", "/search?q=test&limit=abc"))
            mvc.perform(get("/api/v1/knowledge/notes" + path)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(10001));
        verifyNoInteractions(notes, search, users);
    }
    @Test void scalarCoercionIsRejectedAndUnicodeBoundaryIsAccepted() throws Exception {
        mvc.perform(post("/api/v1/knowledge/notes").contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("title", 123, "content", "x", "category", "面试复盘"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(10004));
        verifyNoInteractions(notes, users);
        when(users.require(null)).thenReturn("owner");
        var draft = new KnowledgeNoteService.Draft("😀".repeat(120), "😀".repeat(30000), "面试复盘");
        mvc.perform(post("/api/v1/knowledge/notes").contentType("application/json").content(json.writeValueAsBytes(draft)))
                .andExpect(status().isOk());
        verify(notes).create("owner", draft);
    }
}
