package com.offerpilot.agent.web;

import com.offerpilot.config.JacksonConfig;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.llm.ModelTier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AgentController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({JacksonConfig.class, AgentController.class})
@ActiveProfiles("test")
class AgentControllerTest {

    @SpringBootConfiguration
    static class TestApplication {
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ModelGateway modelGateway;

    @Test
    void chatStartsSseStream() throws Exception {
        when(modelGateway.defaultTier()).thenReturn(ModelTier.FLASH);
        when(modelGateway.modelName(ModelTier.FLASH)).thenReturn("qwen3.8-flash");

        mockMvc.perform(post("/api/v1/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"content\":\"你好，职航\"}"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());
    }
}
