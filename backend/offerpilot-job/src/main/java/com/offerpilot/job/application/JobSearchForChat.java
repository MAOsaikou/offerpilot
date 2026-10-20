package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 聊天用的有界搜索快照；岗位详情继续通过已有岗位接口读取。 */
@Service
public class JobSearchForChat {
    // MCP 的站点名称主要是英文；只映射明确的企业别名，仍须在实时目录中校验。
    // “阿里”等跨多个招聘主体的名称不做模糊匹配。
    private static final Map<String, String> SITE_ALIASES = Map.ofEntries(
            Map.entry("滴滴", "didi"), Map.entry("滴滴出行", "didi"), Map.entry("快手", "kuaishou"),
            Map.entry("字节", "bytedance"), Map.entry("字节跳动", "bytedance"), Map.entry("美团", "meituan"),
            Map.entry("小米", "xiaomi"), Map.entry("腾讯", "tencent"), Map.entry("百度", "baidu"),
            Map.entry("京东", "jd"), Map.entry("小红书", "xiaohongshu"), Map.entry("哔哩哔哩", "bilibili"),
            Map.entry("B站", "bilibili"), Map.entry("网易", "netease"), Map.entry("携程", "ctrip"),
            Map.entry("华为", "huawei"), Map.entry("大疆", "dji"), Map.entry("蚂蚁", "ant"),
            Map.entry("蚂蚁集团", "ant"), Map.entry("得物", "dewu"), Map.entry("米哈游", "mihoyo"),
            Map.entry("稀宇科技", "minimax"), Map.entry("月之暗面", "moonshot"), Map.entry("智谱", "zhipu"),
            Map.entry("深度求索", "deepseek"), Map.entry("淘天", "taotian"), Map.entry("淘天集团", "taotian"),
            Map.entry("淘宝闪购", "taobao-shangou"), Map.entry("飞猪", "fliggy"), Map.entry("阿里国际", "alibaba-intl"),
            Map.entry("阿里云", "aliyun"), Map.entry("通义实验室", "tongyi"), Map.entry("钉钉", "dingtalk"),
            Map.entry("平头哥", "thead"), Map.entry("高德", "amap"), Map.entry("菜鸟", "cainiao"),
            Map.entry("虎鲸文娱", "hujing"), Map.entry("盒马", "freshippo"), Map.entry("阿里健康", "alihealth"),
            Map.entry("灵犀互娱", "lingxi"));
    private final JobService jobs;
    private final ObjectMapper json;

    public JobSearchForChat(JobService jobs, ObjectMapper json) {
        this.jobs = jobs;
        this.json = json;
    }

    public ObjectNode search(String query, String site, String nature, String location) {
        if (query == null || query.isBlank() || query.length() > 100 || site == null || site.length() > 60
                || location == null || location.length() > 60 || nature == null || !Set.of("campus", "intern", "social", "all").contains(nature))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "搜索条件无效，请提供岗位关键词并检查城市、企业与招聘类型");
        String resolvedSite = SITE_ALIASES.getOrDefault(site.strip(), site.strip());
        if (!resolvedSite.isEmpty()) {
            JsonNode selected = null;
            for (JsonNode candidate : jobs.sites().path("sites")) {
                if (resolvedSite.equalsIgnoreCase(candidate.path("id").asText()) || resolvedSite.equalsIgnoreCase(candidate.path("name").asText())) {
                    selected = candidate;
                    break;
                }
            }
            if (selected == null) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "未识别该企业，请在岗位搜索页选择支持的企业后重试");
            if (!"all".equals(nature) && selected.path("supportedNatures").isArray()
                    && !selected.path("supportedNatures").isEmpty()) {
                boolean supported = false;
                for (var value : selected.path("supportedNatures")) if (nature.equals(value.asText())) supported = true;
                if (!supported) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "该企业不支持所选招聘类型，请更换类型或企业");
            }
            resolvedSite = selected.path("id").asText();
        }
        JsonNode result = jobs.search(query.strip(), resolvedSite, nature, location.strip());
        ObjectNode card = json.createObjectNode().put("schemaVersion", 2).put("returnedCount", result.path("items").size());
        card.set("criteria", json.valueToTree(java.util.Map.of("query", query.strip(), "site", resolvedSite,
                "nature", nature, "location", location.strip())));
        card.put("fetchedAt", result.path("fetchedAt").asText());
        var items = card.putArray("items");
        for (JsonNode job : result.path("items")) {
            if (items.size() >= 5) break;
            var item = items.addObject();
            for (String field : List.of("id", "site", "title", "company", "locations", "nature", "updatedAt", "source", "snapshotDate", "retrievalSource", "storedAt"))
                item.put(field, bounded(job.path(field).asText(), 200));
        }
        var meta = card.putObject("meta");
        for (String field : List.of("source", "snapshotDate", "sourceDescription", "fallbackReason"))
            if (result.path("meta").has(field)) meta.put(field, bounded(result.path("meta").path(field).asText(), 300));
        for(String field:List.of("partial","timedOut","vectorAvailable"))
            if(result.path("meta").has(field)) meta.put(field,result.path("meta").path(field).asBoolean());
        if(result.path("meta").has("durationMs")) meta.put("durationMs",result.path("meta").path("durationMs").asLong());
        meta.put("cached", result.path("meta").path("cached").asBoolean());
        meta.put("truncated", result.path("meta").path("truncated").asBoolean());
        for (String field : List.of("sites", "degradedSites")) {
            var array = meta.putArray(field);
            for (JsonNode value : result.path("meta").path(field)) {
                if (array.size() >= 100) break;
                array.add(bounded(value.asText(), 60));
            }
        }
        var skipped = meta.putArray("skipped");
        for (JsonNode value : result.path("meta").path("skipped")) {
            if (skipped.size() >= 100) break;
            // 不把外部异常正文存进聊天记录。
            skipped.addObject().put("site", bounded(value.path("site").asText(), 60)).put("reason", "暂不可用");
        }
        if (card.toString().length() > 32768) throw new BusinessException(ErrorCode.TOOL_FAILED, "搜索结果过大，请缩小查询范围");
        return card;
    }

    private static String bounded(String value, int limit) {
        return value.length() > limit ? com.offerpilot.common.text.TextBounds.truncate(value, limit) : value;
    }
}
