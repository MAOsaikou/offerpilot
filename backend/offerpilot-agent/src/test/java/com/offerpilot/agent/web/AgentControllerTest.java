package com.offerpilot.agent.web;

import com.offerpilot.config.JacksonConfig;
import com.offerpilot.agent.application.ChatService;
import com.offerpilot.agent.domain.AgentMode;
import com.offerpilot.agent.event.AgentEvent;
import reactor.core.publisher.Flux;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
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

    @MockBean
    private ChatService chatService;

    @Test
    void chatStartsSseStream() throws Exception {
        when(chatService.chat(any())).thenReturn(Flux.just(
                new AgentEvent.RunStarted("run.started", 1, "run_test", Instant.now(), "conv_test", "msg_test", "deepseek-v4-flash", AgentMode.AUTO),
                new AgentEvent.TextDelta("text.delta", 2, "run_test", Instant.now(), null, "你好，职航"),
                new AgentEvent.RunCompleted("run.completed", 3, "run_test", Instant.now(), "msg_test", "COMPLETED", 10)));

        var result = mockMvc.perform(post("/api/v1/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"content\":\"你好，职航\"}"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted()).andReturn();
        var completed = mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn();
        assertThat(completed.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("event:run.started", "event:text.delta", "你好，职航", "event:run.completed", "id:3");
        assertThat(completed.getResponse().getHeader("X-Accel-Buffering")).isEqualTo("no");
    }

    @Test
    void emptyInputIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/agent/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\" \"}")).andExpect(status().isBadRequest());
    }
}
