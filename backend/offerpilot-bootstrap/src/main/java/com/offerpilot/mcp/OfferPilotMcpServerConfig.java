package com.offerpilot.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "spring.ai.mcp.server.enabled", havingValue = "true")
public class OfferPilotMcpServerConfig {
    @Bean
    ToolCallbackProvider offerPilotMcpToolCallbacks(OfferPilotMcpTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
