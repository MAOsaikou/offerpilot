package com.offerpilot.common.error;

import com.offerpilot.common.web.TraceIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Exercise real MVC route/resource resolution rather than throwing an exception from a test controller. */
class MissingRouteTest {
    @Test
    void missingApiAndStaticResourcesRemain404() throws Exception {
        try (var context = context(StaticResources.class)) {
            MockMvc mvc = mvc(context);
            for (var request : java.util.List.of(get("/api/v1/no-such-endpoint"),
                    post("/api/v1/interviews/iv_smoke/voice"), get("/missing-asset.js"))) {
                mvc.perform(request.header("X-Trace-Id", "missing-resource-test"))
                        .andExpect(status().isNotFound())
                        .andExpect(content().contentTypeCompatibleWith("application/json"))
                        .andExpect(jsonPath("$.code").value(10005))
                        .andExpect(jsonPath("$.message").value("接口或资源不存在"))
                        .andExpect(jsonPath("$.traceId").value("missing-resource-test"));
            }
        }
    }

    @Test
    void noHandlerWithoutStaticFallbackAlsoRemains404() throws Exception {
        try (var context = context(NoResources.class)) {
            mvc(context).perform(get("/api/v1/no-such-endpoint"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value(10005));
        }
    }

    private AnnotationConfigWebApplicationContext context(Class<?> config) {
        var context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(config);
        context.refresh();
        return context;
    }

    private MockMvc mvc(AnnotationConfigWebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).addFilters(new TraceIdFilter()).build();
    }

    @Configuration
    @EnableWebMvc
    @Import(GlobalExceptionHandler.class)
    static class NoResources {}

    @Configuration
    @EnableWebMvc
    @Import(GlobalExceptionHandler.class)
    static class StaticResources implements WebMvcConfigurer {
        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/**").addResourceLocations("classpath:/missing-static-root/");
        }
    }
}
