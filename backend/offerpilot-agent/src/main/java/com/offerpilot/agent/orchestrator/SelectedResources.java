package com.offerpilot.agent.orchestrator;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tell classifiers which resources are selected without asking models to handle their IDs. */
final class SelectedResources {
    private SelectedResources() {}

    static String describe(Map<String, Object> context) {
        var selected = new LinkedHashMap<String, Boolean>();
        for (String key : List.of("jobId", "resumeId", "resumeVersionId", "applicationId"))
            selected.put(key, context.get(key) instanceof String value && !value.isBlank() && value.length() <= 80);
        return "调用方资源选择状态（true=已选择，标识由服务端绑定，不要求用户再粘贴）：" + selected;
    }
}
