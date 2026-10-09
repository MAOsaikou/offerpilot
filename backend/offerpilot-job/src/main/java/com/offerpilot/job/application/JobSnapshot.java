package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

/** Immutable, curated sample data shared with jobs-mcp; never an official opening. */
@Component
public class JobSnapshot {
    private final ObjectMapper json;
    private final JsonNode dataset;

    public JobSnapshot(ObjectMapper json) {
        this.json = json;
        try (var stream = JobSnapshot.class.getResourceAsStream("/jobs/snapshot.json")) {
            if (stream == null) throw new IllegalStateException("Missing jobs snapshot");
            dataset = json.readTree(stream);
            if (!dataset.path("jobs").isArray() || dataset.path("snapshotDate").asText().isBlank())
                throw new IllegalStateException("Invalid jobs snapshot");
        } catch (IOException ex) { throw new IllegalStateException("Cannot read jobs snapshot", ex); }
    }

    public ObjectNode sites() {
        var result = json.createObjectNode();
        result.set("sites", dataset.path("sites").deepCopy());
        result.set("_meta", metadata());
        return result;
    }

    public ObjectNode search(String query, String site, String nature, String location, int limit, String reason) {
        String selected = site.strip();
        if (!selected.isEmpty()) {
            for (var candidate : dataset.path("sites"))
                if (selected.equalsIgnoreCase(candidate.path("name").asText())) selected = candidate.path("id").asText();
        }
        final String target = selected;
        var tokens = Arrays.stream(query.strip().toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(token -> !token.isBlank()).toList();
        var matches = new ArrayList<JsonNode>();
        for (var job : dataset.path("jobs")) {
            if (!target.isEmpty() && !target.equalsIgnoreCase(job.path("site").asText())) continue;
            if (!"all".equals(nature) && !nature.equals(job.path("nature").asText())) continue;
            if (!job.path("locations").asText().contains(location.strip())) continue;
            if (tokens.stream().allMatch(token -> searchable(job).contains(token))) matches.add(job);
        }
        matches.sort(Comparator.<JsonNode>comparingInt(job -> score(job, tokens)).reversed()
                .thenComparing(job -> job.path("id").asText()));
        var result = json.createObjectNode();
        var jobs = result.putArray("jobs");
        matches.stream().limit(limit).forEach(job -> jobs.add(job.deepCopy()));
        var meta = metadata();
        meta.put("query", query.strip()).put("nature", nature).put("total", jobs.size())
                .put("truncated", matches.size() > limit).put("cached", false);
        var sites = meta.putArray("sites");
        if (!target.isEmpty()) sites.add(target);
        else for (var candidate : dataset.path("sites")) sites.add(candidate.path("id").asText());
        meta.putArray("skipped");
        meta.putArray("degradedSites");
        if (reason != null) meta.put("fallbackReason", reason);
        result.set("_meta", meta);
        return result;
    }

    public JsonNode detail(String site, String externalId) {
        for (var job : dataset.path("jobs"))
            if (site.equals(job.path("site").asText()) && externalId.equals(job.path("id").asText()))
                return job.deepCopy();
        throw new BusinessException(ErrorCode.JOB_NOT_FOUND);
    }

    private ObjectNode metadata() {
        return json.createObjectNode().put("source", "LOCAL_SNAPSHOT")
                .put("snapshotDate", dataset.path("snapshotDate").asText())
                .put("sourceDescription", dataset.path("sourceDescription").asText());
    }

    private static String searchable(JsonNode job) {
        return (job.path("title").asText() + " " + job.path("company").asText() + " "
                + job.path("category").asText() + " " + job.path("description").asText() + " "
                + job.path("requirement").asText() + " " + job.path("skills")).toLowerCase(Locale.ROOT);
    }

    private static int score(JsonNode job, List<String> tokens) {
        String title = job.path("title").asText().toLowerCase(Locale.ROOT);
        return tokens.stream().mapToInt(token -> title.contains(token) ? 10 : 1).sum();
    }
}
