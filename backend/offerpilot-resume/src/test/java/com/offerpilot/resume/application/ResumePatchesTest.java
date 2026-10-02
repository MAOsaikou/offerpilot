package com.offerpilot.resume.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ResumePatchesTest {
    final ObjectMapper json = new ObjectMapper();
    ObjectNode source() throws Exception {
        ObjectNode source = (ObjectNode) json.readTree(ResumeStructure.SCHEMA);
        source.put("summary", "负责后端开发");
        ((ArrayNode) source.get("projects")).removeAll().add(json.readTree("""
            {"name":"校园平台","role":"后端","start":"2025","end":"2026","tech":["Java"],"bullets":["实现登录","优化缓存"]}
            """));
        return source;
    }
    ObjectNode patch(String id, String path, String from, String value) {
        return json.createObjectNode().put("id", id).put("op", "replace").put("path", path)
            .put("from", from).put("value", value).put("reason", "突出真实贡献").put("needsUserConfirmation", false);
    }
    ArrayNode patches() {
        return json.createArrayNode().add(patch("p1", "/summary", "负责后端开发", "设计与实现后端服务"))
            .add(patch("p2", "/projects/0/bullets/0", "实现登录", "实现登录与权限校验"))
            .add(patch("p3", "/projects/0/bullets/1", "优化缓存", "优化缓存命中率"));
    }
    @Test void appliesEditedSubsetWithoutChangingOriginalOrUnselectedPaths() throws Exception {
        var source = source(); var original = source.deepCopy();
        var decision = ResumePatches.decision(json.readTree("""
            {"batchId":"pb_fixture","accepted":["p2"],"edited":{"p1":{"value":"本人设计与实现后端服务"}}}
            """));
        var applied = ResumePatches.apply(source, patches(), decision);
        assertThat(applied.path("summary").asText()).isEqualTo("本人设计与实现后端服务");
        assertThat(applied.at("/projects/0/bullets/0").asText()).isEqualTo("实现登录与权限校验");
        assertThat(applied.at("/projects/0/bullets/1").asText()).isEqualTo("优化缓存");
        assertThat(applied.path("basics")).isEqualTo(source.path("basics"));
        assertThat(source).isEqualTo(original);
    }
    @Test void rejectsUnsafePathsOperationsMissingTargetsAndNonTextLeaves() throws Exception {
        for (String path : List.of("/basics/phone", "/projects/0/role", "/projects/0/start", "/projects/0/bullets",
                "/projects/9/bullets/0", "/projects/00/bullets/0", "/projects/0/bullets/-", "/projects/0/bullets/~01",
                "/confidence/summary", "/summary/x", "/projects/0/bullets/999999999999")) {
            var batch = json.createArrayNode().add(patch("p1", path, "实现登录", "实现权限校验"));
            assertThatThrownBy(() -> ResumePatches.validateBatch(source(), batch)).hasMessageContaining("补丁");
        }
        for (String op : List.of("add", "remove", "move", "copy", "test")) {
            var batch = patches(); ((ObjectNode) batch.get(0)).put("op", op);
            assertThatThrownBy(() -> ResumePatches.validateBatch(source(), batch)).hasMessageContaining("补丁");
        }
    }
    @Test void rejectsDuplicateIdsPathsInventedOriginalUnknownFieldsAndNoOpSuggestions() throws Exception {
        for (String field : List.of("id", "path", "from", "extra", "value")) {
            var batch = patches(); var first = (ObjectNode) batch.get(0);
            switch (field) {
                case "id" -> first.put("id", "p2");
                case "path" -> first.put("path", "/projects/0/bullets/0");
                case "from" -> first.put("from", "不存在的原文");
                case "value" -> first.put("value", "负责后端开发");
                default -> first.put("extra", "hidden");
            }
            assertThatThrownBy(() -> ResumePatches.validateBatch(source(), batch)).hasMessageContaining("补丁");
        }
    }
    @Test void confirmationIsRequiredEvenForEditedFlaggedSuggestion() throws Exception {
        var batch = patches(); ((ObjectNode) batch.get(0)).put("needsUserConfirmation", true).put("confirmationHint", "请核对个人贡献");
        var body = (ObjectNode) json.readTree("{\"batchId\":\"pb_fixture\",\"accepted\":[],\"edited\":{\"p1\":{\"value\":\"本人实现后端服务\"}}}");
        assertThatThrownBy(() -> ResumePatches.apply(source(), batch, ResumePatches.decision(body))).hasMessageContaining("补丁");
        body.putArray("confirmed").add("p1");
        assertThat(ResumePatches.apply(source(), batch, ResumePatches.decision(body)).path("summary").asText()).isEqualTo("本人实现后端服务");
        ((ObjectNode) batch.get(0)).remove("confirmationHint");
        assertThatThrownBy(() -> ResumePatches.validateBatch(source(), batch)).hasMessageContaining("补丁");
    }
    @Test void rejectsMalformedDecisionsAndUnknownSelection() throws Exception {
        for (String body : List.of("null", "{}",
                "{\"batchId\":\"pb_fixture\",\"accepted\":[]}",
                "{\"batchId\":\"pb_fixture\",\"accepted\":[\"p1\",\"p1\"]}",
                "{\"batchId\":\"pb_fixture\",\"accepted\":[\"p1\"],\"confirmed\":[\"p2\"]}",
                "{\"batchId\":\"pb_fixture\",\"accepted\":[\"p1\"],\"edited\":{\"p1\":{\"value\":\"edit\"}}}",
                "{\"batchId\":\"pb_fixture\",\"accepted\":[\"p1\"],\"edited\":null}",
                "{\"batchId\":\"pb_fixture\",\"accepted\":[\"p1\"],\"edited\":{\"p2\":{\"value\":12}}}",
                "{\"batchId\":\"pb_fixture\",\"accepted\":[\"p1\"],\"edited\":{\"p2\":{\"value\":\"x\",\"path\":\"/basics/name\"}}}",
                "{\"batchId\":\"pb_fixture\",\"accepted\":[\"p1\"],\"userId\":\"other\"}")) {
            assertThatThrownBy(() -> ResumePatches.decision(json.readTree(body))).hasMessageContaining("补丁");
        }
        for (String selected : List.of("missing", "p1")) {
            var body = json.createObjectNode().put("batchId", "pb_fixture"); body.putArray("accepted");
            body.withObject("/edited").withObject("/" + selected).put("value", "负责后端开发");
            assertThatThrownBy(() -> ResumePatches.apply(source(), patches(), ResumePatches.decision(body))).hasMessageContaining("补丁");
        }
    }
    @Test void canonicalizesDecisionOrderWithoutLosingUserEdits() throws Exception {
        JsonNode a = json.readTree("{\"batchId\":\"pb_fixture\",\"accepted\":[\"p3\",\"p1\"],\"confirmed\":[\"p1\",\"p3\"]}");
        JsonNode b = json.readTree("{\"batchId\":\"pb_fixture\",\"accepted\":[\"p1\",\"p3\"],\"edited\":{},\"confirmed\":[\"p3\",\"p1\"]}");
        assertThat(ResumePatches.decision(a)).isEqualTo(ResumePatches.decision(b));
    }
    @Test void validatesLimitsTypesAndFinalStructureSize() throws Exception {
        var batch = patches(); ((ObjectNode) batch.get(0)).put("value", "x".repeat(5001));
        assertThatThrownBy(() -> ResumePatches.validateBatch(source(), batch)).hasMessageContaining("补丁");
        ((ObjectNode) batch.get(0)).put("value", "x").put("needsUserConfirmation", "false");
        assertThatThrownBy(() -> ResumePatches.validateBatch(source(), batch)).hasMessageContaining("补丁");
        var tooMany = json.createArrayNode(); for (int i = 0; i < 51; i++) tooMany.add(patches().get(0));
        assertThatThrownBy(() -> ResumePatches.validateBatch(source(), tooMany)).hasMessageContaining("补丁");
        var source = source(); var bullets = (ArrayNode) source.at("/projects/0/bullets"); bullets.removeAll();
        var large = json.createArrayNode(); var body = json.createObjectNode().put("batchId", "pb_fixture");
        var accepted = body.putArray("accepted");
        for (int i = 0; i < 21; i++) {
            bullets.add("原文" + i); String id = "p" + i;
            large.add(patch(id, "/projects/0/bullets/" + i, "原文" + i, "x".repeat(5000))); accepted.add(id);
        }
        assertThatThrownBy(() -> ResumePatches.apply(source, large, ResumePatches.decision(body))).hasMessageContaining("简历结构");
    }
}
