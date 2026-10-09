package com.offerpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.auth.application.DemoAccountBootstrap;
import com.offerpilot.auth.application.PasswordLoginService;
import com.offerpilot.llm.ModelTier;
import com.offerpilot.mcp.JobsMcpClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Paid opt-in, at most 30 chat rounds total; each loop is a fresh three-turn conversation. */
@Tag("eval")
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "OFFERPILOT_RUN_LIVE_SMOKE", matches = "true")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".*\\S.*")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.openai.api-key=sk-not-configured", "spring.ai.model.embedding=openai",
        "offerpilot.security.jwt-secret=sse-live-isolated-signing-key-not-for-production-0123456789abcdef0123456789abcdef",
        "offerpilot.demo.enabled=true", "offerpilot.demo.email=sse-demo@example.test",
        "offerpilot.demo.password=fictional-sse-demo-password", "offerpilot.demo.reset-cron=-",
        "offerpilot.resume.worker-enabled=false", "spring.ai.mcp.server.enabled=false",
        "offerpilot.staff-bootstrap.email=", "offerpilot.staff-bootstrap.password=",
        "offerpilot.knowledge.occupation-embed.enabled=false", "spring.ai.retry.max-attempts=1"
})
@ActiveProfiles("prod")
@Testcontainers
@Import(DemoJourneyIntegrationTest.StorageConfig.class)
class DeepSeekSseLiveEvaluationTest {
    @Container static PostgreSQLContainer<?> database = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindIp("127.0.0.1"), new ExposedPort(5432))));
    @Container static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withPortBindings(
                    new PortBinding(Ports.Binding.bindIp("127.0.0.1"), new ExposedPort(6379))));
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", database::getJdbcUrl);
        registry.add("spring.datasource.username", database::getUsername);
        registry.add("spring.datasource.password", database::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.ai.deepseek.api-key", () -> System.getenv("DEEPSEEK_API_KEY"));
    }
    @MockBean JobsMcpClient jobs;
    @Autowired ObjectMapper json;
    @Autowired DemoAccountBootstrap accounts;
    @Autowired PasswordLoginService identities;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @ParameterizedTest @EnumSource(value = ModelTier.class, names = {"FLASH", "PRO"})
    void threeTurnInterviewLoops(ModelTier tier, CapturedOutput output) throws Exception {
        String token = accounts.login(identities).accessToken();
        String session = jdbc.queryForObject("SELECT id FROM interview_sessions WHERE user_id=? AND completed=true LIMIT 1",
                String.class, accounts.requireAccount());
        var prompts = List.of("请评价这次面试，概括STAR评分卡并提一个改进点。限120字。",
                "接着上一轮，请结合卡片指出回答里的行动和结果还可以怎样表达。限120字，不编造经历。",
                "继续这次文字面试复盘，给我一个下一轮练习问题和简短建议。限120字。");
        int clean = 0, errors = 0, eof = 0, passedLoops = 0;
        {
            var client = HttpClient.newHttpClient();
            for (int loop = 1; loop <= 5; loop++) {
                String conversation = null;
                boolean passed = true;
                for (int round = 0; round < 3; round++) {
                    var body = new LinkedHashMap<String, Object>();
                    body.put("content", prompts.get(round)); body.put("mode", "INTERVIEWER");
                    if (conversation != null) body.put("conversationId", conversation);
                    body.put("options", Map.of("modelTier", tier.name(), "context",
                            Map.of("interviewAction", "scoreInterview", "interviewSessionId", session)));
                    try {
                        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/agent/chat"))
                                .timeout(Duration.ofSeconds(240)).header("Authorization", "Bearer " + token)
                                .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
                        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                        boolean terminal = false, failed = false;
                        int text = 0;
                        for (String line : response.body().split("\\R")) {
                            if (!line.startsWith("data:")) continue;
                            var event = json.readTree(line.substring(5).strip());
                            String type = event.path("type").asText();
                            if (type.equals("run.started")) conversation = event.path("conversationId").asText();
                            if (type.equals("text.delta")) text += event.path("text").asText().length();
                            if (type.equals("run.completed")) terminal = true;
                            if (type.equals("error")) { failed = true; terminal = true; }
                        }
                        if (response.statusCode() != 200 || failed) { errors++; passed = false; }
                        else if (!terminal || text == 0 || conversation == null) { eof++; passed = false; }
                        else clean++;
                    } catch (java.io.IOException transport) { eof++; passed = false; }
                    System.out.printf("LIVE_SSE %s loop=%d round=%d clean=%d error=%d eof=%d%n", tier, loop, round + 1, clean, errors, eof);
                    if (!passed) break; // Bound calls and avoid issuing a new turn into a failed conversation.
                }
                if (passed) passedLoops++;
                System.out.printf("LIVE_SSE %s loops=%d/5 rounds=%d error=%d eof=%d%n", tier, passedLoops, clean, errors, eof);
            }
        }
        assertThat(passedLoops).as(tier + " clean three-turn conversations").isEqualTo(5);
        assertThat(clean).isEqualTo(15);
        assertThat(errors + eof).isZero();
        assertThat(output.getAll()).doesNotContain("NullPointerException", "Unhandled exception", "onErrorDropped");
    }
}
