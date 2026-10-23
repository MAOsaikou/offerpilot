package com.offerpilot.career.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.knowledge.application.OccupationHybridRetriever;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real seed, real Pearson/fit scoring and card projection; SQL/model ports are offline fixtures. */
class CareerFitRecommendationTest {
    private final ObjectMapper json = new ObjectMapper();
    private static final String MAJOR = "计算机科学与技术专业，大三";
    private static final String SKILLS = "平时喜欢写代码做小项目";
    private static final String GOAL = "希望五年内成为资深后端工程师";
    private final CareerAssessmentService.Confirmed confirmed = new CareerAssessmentService.Confirmed(
            Map.of("R", 45, "I", 75, "A", 20, "S", 20, "E", 20, "C", 40), "IRC", List.of(),
            new CareerAssessmentInference.Constraints("", "", ""));

    private List<JsonNode> seed() throws Exception {
        var out = new ArrayList<JsonNode>();
        for (String file : List.of("occupations.json", "occupations-onet.json")) {
            json.readTree(Files.readString(Path.of("../offerpilot-bootstrap/src/main/resources/seed/" + file)))
                    .path("occupations").forEach(out::add);
        }
        return out;
    }

    private JdbcTemplate catalog(List<JsonNode> rows) throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var resultSets = new LinkedHashMap<String, ResultSet>();
        for (var node : rows) {
            var rs = mock(ResultSet.class);
            for (String[] field : List.of(new String[]{"id", "id"}, new String[]{"name", "name"},
                    new String[]{"family", "family"}, new String[]{"holland_code", "hollandCode"},
                    new String[]{"description", "description"}, new String[]{"source", "source"},
                    new String[]{"growth_path", "growthPath"})) {
                when(rs.getString(field[0])).thenReturn(node.path(field[1]).asText(""));
            }
            for (String[] field : List.of(new String[]{"holland_scores", "hollandScores"},
                    new String[]{"core_skills", "coreSkills"}, new String[]{"salary_range", "salaryRange"})) {
                when(rs.getString(field[0])).thenReturn(node.path(field[1]).toString());
            }
            for (String[] field : List.of(new String[]{"typical_titles", "typicalTitles"},
                    new String[]{"aliases", "aliases"}, new String[]{"related_majors", "relatedMajors"},
                    new String[]{"companies", "companies"})) {
                var values = new ArrayList<String>();
                node.path(field[1]).forEach(v -> values.add(v.asText()));
                var array = mock(java.sql.Array.class);
                when(array.getArray()).thenReturn(values.toArray(String[]::new));
                when(rs.getArray(field[0])).thenReturn(array);
            }
            resultSets.put(node.path("id").asText(), rs);
        }
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any())).thenAnswer(call -> {
            RowMapper<Object> mapper = call.getArgument(1);
            var mapped = new ArrayList<>();
            for (var rs : resultSets.values()) mapped.add(mapper.mapRow(rs, mapped.size()));
            return mapped;
        });
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), any(Object.class))).thenAnswer(call -> {
            RowMapper<Object> mapper = call.getArgument(1);
            var rs = resultSets.get(call.getArgument(2, String.class));
            return rs == null ? List.of() : List.of(mapper.mapRow(rs, 0));
        });
        return jdbc;
    }

    private DefaultCareerRecommendForChat service(JdbcTemplate jdbc, OccupationHybridRetriever retriever) {
        var profiles = mock(CareerProfileService.class);
        // All facts are in assessment answers; goal is outside the old subList(1,4) retrieval window.
        when(profiles.get("alice")).thenReturn(new CareerProfileService.Profile("", "", "", List.of(), List.of(), null));
        var assessments = mock(CareerAssessmentService.class);
        when(assessments.confirmed("alice")).thenReturn(confirmed);
        when(assessments.get("alice")).thenReturn(new CareerAssessmentService.Assessment(
                CareerAssessmentService.QUESTIONS, List.of(MAJOR, SKILLS, "", "", "", GOAL, ""), null, confirmed, "now"));
        var service = new DefaultCareerRecommendForChat(profiles, new OccupationRecommender(jdbc, retriever), assessments);
        ReflectionTestUtils.setField(service, "details", new CareerCardDetails(jdbc, json));
        return service;
    }

    @Test void csJuniorBackendGoalRecallsOfficialSoftwareDeveloperInTopThreeWithHonestScoresAndSources() throws Exception {
        var rows = seed();
        assertThat(rows).hasSize(402);
        var jdbc = catalog(rows);
        var service = service(jdbc, null);
        var result = service.recommend("alice");
        assertThat(result.guidance()).isNull();
        var cards = result.payload().recommendations();
        assertThat(cards).hasSizeBetween(5, 8);
        assertThat(cards.subList(0, 3)).extracting(CareerRecommendForChat.Recommendation::occupationId)
                .contains("occ_onet_15-1252.00");
        assertThat(cards).extracting(CareerRecommendForChat.Recommendation::occupationId)
                .doesNotContain("occ_onet_41-3021.00").doesNotHaveDuplicates();
        assertThat(cards).isSortedAccordingTo(Comparator.comparingDouble(
                (CareerRecommendForChat.Recommendation c) -> c.rankingScore()).reversed());
        var software = cards.stream().filter(c -> c.occupationId().equals("occ_onet_15-1252.00")).findFirst().orElseThrow();
        assertThat(software.rankingScore()).isCloseTo(.45 * software.similarity() + .30 * .85 + .15 * .75 + .10, within(1e-12));
        assertThat(cards).allSatisfy(card -> {
            assertThat(card.rankingScore()).isGreaterThanOrEqualTo(CareerFitScoring.MIN_FIT);
            assertThat(String.join(" ", card.reasons())).contains(MAJOR, SKILLS, "career-fit-v1", "实际综合排序分", "目标", "专业", "自述技能");
            var source = rows.stream().filter(n -> n.path("id").asText().equals(card.occupationId())).findFirst().orElseThrow();
            assertThat(card.details().sourceOccupationId()).isEqualTo(card.occupationId());
            assertThat(card.details().source()).isEqualTo(source.path("source").asText());
            assertThat(card.citations()).allSatisfy(c -> {
                assertThat(c.occupationId()).isEqualTo(card.occupationId());
                assertThat(source.path("description").asText()).contains(c.excerpt());
            });
            assertThat(card.details().salaryRange()).isEqualTo("未提供");
        });
        assertThat(service.recommend("alice")).isEqualTo(result);

        // Persisted baseline must be the actual score, then survive approved rerank without accumulation.
        var revisions = new CareerRecommendationService(jdbc, json, service, mock(CareerProfileService.class));
        var initial = revisions.create("alice");
        assertThat(initial.items()).extracting(CareerRecommendationService.Item::baseScore)
                .containsExactlyElementsOf(cards.stream().map(CareerRecommendForChat.Recommendation::rankingScore).toList());
        var reranked = CareerRecommendationService.rescore(initial,
                new CareerRecommendationService.Constraints("杭州", 30, true, true), 0, () -> 0);
        assertThat(reranked.items()).allSatisfy(item -> {
            assertThat(item.score()).isCloseTo(item.baseScore() + .25 * item.occupation().riasec().get("I"), within(1e-12));
            assertThat(item.changeReason()).contains(MAJOR, SKILLS, "career-fit-v1", "无可比薪资", "基础综合分");
        });
        var restored = CareerRecommendationService.rescore(reranked, CareerRecommendationService.Constraints.empty(), 0, () -> 0);
        assertThat(restored.items()).extracting(CareerRecommendationService.Item::score)
                .containsExactlyElementsOf(initial.items().stream().map(CareerRecommendationService.Item::score).toList());
        assertThat(json.readValue(json.writeValueAsString(initial), CareerRecommendationService.Revision.class)).isEqualTo(initial);
        assertThat(restored.items()).allSatisfy(item -> assertThat(item.baseReason()).contains(MAJOR, SKILLS, "career-fit-v1"));
    }

    @Test void unmatchedSavedTargetStillReturnsEightInterestBasedCardsWithThreeReasons() throws Exception {
        var jdbc = catalog(seed());
        var profiles = mock(CareerProfileService.class);
        var interests = List.of("技术研发", "数据分析");
        when(profiles.get("alice")).thenReturn(new CareerProfileService.Profile(
                "原目标", "上海", "Java", interests, List.of(), null));
        var service = new DefaultCareerRecommendForChat(profiles, new OccupationRecommender(jdbc));
        var result = service.recommend("alice", "职业推荐验收：我不知道适合什么");
        assertThat(result.guidance()).isNull();
        assertThat(result.payload().hollandCode()).isEqualTo("IRC");
        assertThat(result.payload().interestsUsed()).containsExactlyElementsOf(interests);
        assertThat(result.currentProfile().target()).isEqualTo("原目标");
        var cards = result.payload().recommendations();
        assertThat(cards).hasSize(8).allSatisfy(card -> {
            assertThat(card.reasons()).hasSize(3);
            assertThat(card.reasons().get(2)).contains("0.0000×目标 0.0000", "目标未使用");
            assertThat(card.rankingScore()).isGreaterThanOrEqualTo(CareerFitScoring.MIN_FIT);
        });
        when(profiles.get("alice")).thenReturn(new CareerProfileService.Profile(
                "", "上海", "Java", interests, List.of(), null));
        var withoutTarget = service.recommend("alice").payload().recommendations();
        assertThat(cards).extracting(CareerRecommendForChat.Recommendation::occupationId)
                .containsExactlyElementsOf(withoutTarget.stream().map(CareerRecommendForChat.Recommendation::occupationId).toList());
        assertThat(cards).extracting(CareerRecommendForChat.Recommendation::rankingScore)
                .containsExactlyElementsOf(withoutTarget.stream().map(CareerRecommendForChat.Recommendation::rankingScore).toList());
        verify(profiles, never()).saveTarget(anyString(), anyString());
    }

    @Test void fiveToSevenFitsAreReturnedWithoutPaddingAndFourGiveExplicitCatalogGuidance() throws Exception {
        var rows = seed();
        var full = service(catalog(rows), null).recommend("alice").payload().recommendations();
        for (int count : List.of(4, 5, 6, 7)) {
            var ids = full.stream().limit(count).map(CareerRecommendForChat.Recommendation::occupationId).toList();
            var limited = rows.stream().filter(n -> ids.contains(n.path("id").asText())
                    || n.path("id").asText().equals("occ_onet_41-3021.00")).toList();
            var result = service(catalog(limited), null).recommend("alice");
            if (count < 5) {
                assertThat(result.payload()).isNull();
                assertThat(result.guidance()).contains("不足5个", "当前4个", "未用低匹配");
            } else {
                assertThat(result.payload().recommendations()).hasSize(count);
            }
        }
    }

    @Test void hybridFailureOrUnrelatedHitsCannotOverrideEvidenceOrChangeDeterministicScores() throws Exception {
        var jdbc = catalog(seed());
        var baseline = service(jdbc, null).recommend("alice").payload().recommendations();
        var retrieval = mock(OccupationHybridRetriever.class);
        when(retrieval.search(anyString(), eq(200))).thenThrow(new IllegalStateException("offline"));
        assertThat(service(jdbc, retrieval).recommend("alice").payload().recommendations()).isEqualTo(baseline);
        when(retrieval.search(anyString(), eq(200))).thenReturn(new OccupationHybridRetriever.Retrieval(List.of(
                new OccupationHybridRetriever.Hit("occ_onet_41-3021.00", "保险销售代理人", "销售", "ECS", 1, "销售保险", true, true)), true));
        var cards = service(jdbc, retrieval).recommend("alice").payload().recommendations();
        assertThat(cards).extracting(CareerRecommendForChat.Recommendation::occupationId)
                .containsExactlyElementsOf(baseline.stream().map(CareerRecommendForChat.Recommendation::occupationId).toList());
        assertThat(cards).extracting(CareerRecommendForChat.Recommendation::rankingScore)
                .containsExactlyElementsOf(baseline.stream().map(CareerRecommendForChat.Recommendation::rankingScore).toList());
        verify(retrieval, times(2)).search(argThat(q -> q.contains(MAJOR) && q.contains(SKILLS) && q.contains(GOAL) && q.contains("软件开发")), eq(200));
    }

    @Test void profileTargetMajorAndSkillsEachContributeAndLatinSkillBoundariesArePreserved() {
        var interest = new RiasecProfile(.45, .75, .2, .2, .2, .4);
        var software = new CareerFitScoring.Catalog("catalog-id", "软件开发工程师", "计算机与数学", "ICR", interest,
                "设计计算机软件", "JavaScript", "", "", "计算机科学与技术");
        var empty = new CareerFitScoring.Context("", "", "", "", "");
        assertThat(CareerFitScoring.score(interest, empty, List.of(software)).get(0).total()).isEqualTo(1);
        // Java is usable for this request because another candidate supports it; JavaScript alone does not.
        var java = new CareerFitScoring.Catalog("java-id", "后端开发工程师", "计算机与数学", "ICR", interest,
                "设计计算机软件", "Java", "", "", "计算机科学与技术");
        var candidates = List.of(software, java);
        var context = new CareerFitScoring.Context("资深后端工程师", MAJOR, "Java", "", "");
        var score = CareerFitScoring.score(interest, context, candidates).get(0);
        assertThat(score.target()).isEqualTo(.85);
        assertThat(score.major()).isEqualTo(1);
        assertThat(score.skills()).isZero();
        assertThat(score.total()).isCloseTo(.45 + .30 * .85 + .15, within(1e-12));
        var javascript = CareerFitScoring.score(interest,
                new CareerFitScoring.Context("资深后端工程师", MAJOR, "JavaScript", "", ""), candidates).get(0);
        assertThat(javascript.skills()).isEqualTo(1);
        assertThat(javascript.total() - score.total()).isCloseTo(.10, within(1e-12));
    }
}
