package com.offerpilot;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Parse actual Compose YAML offline, then check dependency closure and readiness without Docker. */
class ComposeConfigurationTest {
    @Test void fullApplicationYamlHasValidDependenciesAndLocalEmbeddingDefaults() throws Exception {
        var options = new LoaderOptions(); options.setAllowDuplicateKeys(false);
        var yaml = new Yaml(new SafeConstructor(options));
        Map<String, Object> config = yaml.load(Files.readString(Path.of("../../deploy/docker-compose.yml")));
        Map<String, Map<String, Object>> services = map(config.get("services"));
        assertThat(services.keySet()).contains("postgres", "redis", "minio", "jobs-mcp", "backend", "frontend", "caddy", "ollama", "ollama-init");
        for (var service : services.entrySet()) {
            var dependencies = ComposeConfigurationTest.<String, Map<String, Object>>map(service.getValue().getOrDefault("depends_on", Map.of()));
            for (var dependency : dependencies.entrySet()) {
                assertThat(services).as("dependency of " + service.getKey()).containsKey(dependency.getKey());
                var target = services.get(dependency.getKey());
                if ("service_healthy".equals(dependency.getValue().get("condition")))
                    assertThat(ComposeConfigurationTest.<String, Object>map(target.get("healthcheck"))).containsKeys("test", "interval", "timeout", "retries");
                if ("service_completed_successfully".equals(dependency.getValue().get("condition")))
                    assertThat(target.get("restart")).isEqualTo("no");
                if (service.getValue().containsKey("profiles") && target.containsKey("profiles"))
                    assertThat((List<?>) target.get("profiles")).isEqualTo(service.getValue().get("profiles"));
            }
            assertNoCycle(service.getKey(), services, new HashSet<>());
        }
        var backend = ComposeConfigurationTest.<String, Object>map(services.get("backend").get("environment"));
        assertThat(backend).containsEntry("JOBS_LIVE_TIMEOUT_MS", "${JOBS_LIVE_TIMEOUT_MS:-6500}")
                .containsEntry("EMBEDDING_BASE_URL", "${EMBEDDING_BASE_URL:-http://ollama:11434}")
                .containsEntry("LLM_MODEL_EMBEDDING", "${LLM_MODEL_EMBEDDING:-bge-m3}")
                .containsEntry("LLM_EMBEDDING_DIMENSIONS", "${LLM_EMBEDDING_DIMENSIONS:-1024}");
        assertThat(ComposeConfigurationTest.<String, Object>map(services.get("jobs-mcp").get("environment")))
                .containsEntry("JOBS_LIVE_TIMEOUT_MS", "${JOBS_LIVE_TIMEOUT_MS:-6500}")
                .containsEntry("SITE_TIMEOUT_MS", "${SITE_TIMEOUT_MS:-6000}");
        Map<String, Object> application = yaml.load(Files.readString(Path.of("src/main/resources/application.yml")));
        var offerpilot = ComposeConfigurationTest.<String, Object>map(application.get("offerpilot"));
        assertThat(ComposeConfigurationTest.<String, Object>map(offerpilot.get("mcp")))
                .containsEntry("jobs-timeout-ms", "${JOBS_LIVE_TIMEOUT_MS:6500}");
        assertThat(services.get("ollama")).doesNotContainKey("ports");
        var volumes = ComposeConfigurationTest.<String, Object>map(config.get("volumes"));
        assertThat(volumes).containsKey("ollama_data");
    }
    private static void assertNoCycle(String name, Map<String, Map<String, Object>> services, Set<String> path) {
        assertThat(path.add(name)).as("dependency cycle at " + name).isTrue();
        var dependencies = ComposeConfigurationTest.<String, Object>map(services.get(name).getOrDefault("depends_on", Map.of()));
        dependencies.keySet().forEach(dependency -> assertNoCycle(dependency, services, new HashSet<>(path)));
    }
    @SuppressWarnings("unchecked")
    private static <K, V> Map<K, V> map(Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<K, V>) value;
    }
}
