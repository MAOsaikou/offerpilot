package com.offerpilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.config.OfferPilotProperties;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.resume.application.ResumeDocumentReader;
import com.offerpilot.resume.application.ResumeExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RS-02 field recall on the generated 24-resume corpus (backend/eval/resume-recall/generate.py).
 * Explicit opt-in only: sends the invented resumes to the configured DeepSeek tier through the same
 * reader + extractor the upload path uses, and writes per-field recall with every miss to
 * target/resume-recall-report.json.
 */
@EnabledIfEnvironmentVariable(named = "OFFERPILOT_RUN_LIVE_RESUME_RECALL", matches = "true")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".*\\S.*")
class ResumeFieldRecallLiveEvaluationTest {
    static final ObjectMapper JSON = new ObjectMapper();

    @Test void fieldRecallOnGeneratedCorpus() throws Exception {
        Path dir = Path.of(System.getenv().getOrDefault("RESUME_RECALL_DIR", "../eval/resume-recall/out"));
        var truth = JSON.readTree(dir.resolve("truth.json").toFile());
        String base = System.getenv().getOrDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com");
        String model = System.getenv().getOrDefault("RESUME_RECALL_MODEL", "deepseek-flash");
        var tier = new OfferPilotProperties.Llm.Tier("deepseek", model, Duration.ofSeconds(120), true);
        var properties = new OfferPilotProperties(null, null, null, null, null,
                new OfferPilotProperties.Llm("FLASH", Map.of("FLASH", tier, "VISION_ALT", tier), Map.of()), null, null);
        var environment = new MockEnvironment().withProperty("spring.ai.deepseek.api-key", System.getenv("DEEPSEEK_API_KEY"))
                .withProperty("spring.ai.deepseek.base-url", base);
        var extractor = new ResumeExtractor(new ModelGateway(properties, environment), JSON, new ClassPathResource("prompts/resume/extract.st"));
        var reader = new ResumeDocumentReader();
        boolean textOnly = !"false".equals(System.getenv("RESUME_RECALL_TEXT_ONLY"));
        String only = System.getenv("RESUME_RECALL_ONLY");

        var score = new Score();
        var outputs = JSON.createObjectNode();
        for (JsonNode expected : truth) {
            String id = expected.path("id").asText();
            if (only != null && !only.contains(id)) continue;
            byte[] bytes = Files.readAllBytes(dir.resolve(expected.path("file").asText()));
            var document = reader.read(bytes, reader.mime(expected.path("file").asText(), bytes));
            // Text layer only by default, so the run measures extraction without page-image fusion.
            if (textOnly) document = new ResumeDocumentReader.Document(document.text(), List.of(), document.channel());
            JsonNode actual;
            try {
                actual = extractor.extractResult(document).structured();
            } catch (RuntimeException failed) {
                score.failures.add(id + ": " + failed.getMessage());
                actual = JSON.createObjectNode();
            }
            outputs.set(id, actual);
            score.resume(id + "/" + expected.path("layout").asText(), expected, actual);
        }
        var report = JSON.createObjectNode();
        report.put("model", model).put("textOnly", textOnly).put("overall", score.overall());
        report.set("fields", JSON.valueToTree(score.recall()));
        report.set("misses", JSON.valueToTree(score.misses));
        report.set("failures", JSON.valueToTree(score.failures));
        report.set("outputs", outputs);
        Files.writeString(Path.of("target/resume-recall-report.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        System.out.println("RS-02 recall overall=" + score.overall() + " fields=" + score.recall());
        assertThat(score.failures).isEmpty();
    }

    /** Hits/total per field path; values are compared after normalising spacing, punctuation and date styles. */
    static final class Score {
        final Map<String, int[]> fields = new TreeMap<>();
        final List<String> misses = new ArrayList<>();
        final List<String> failures = new ArrayList<>();

        double overall() {
            int hit = 0, total = 0;
            for (int[] v : fields.values()) { hit += v[0]; total += v[1]; }
            return total == 0 ? 0 : Math.round(hit * 1000.0 / total) / 1000.0;
        }
        Map<String, String> recall() {
            var out = new TreeMap<String, String>();
            fields.forEach((k, v) -> out.put(k, String.format("%.3f (%d/%d)", v[0] * 1.0 / v[1], v[0], v[1])));
            return out;
        }
        void check(String resume, String field, boolean hit, Object expected) {
            var counts = fields.computeIfAbsent(field, k -> new int[2]);
            counts[1]++;
            if (hit) counts[0]++; else misses.add(resume + " " + field + " expected=" + expected);
        }

        void resume(String id, JsonNode e, JsonNode a) {
            var eb = e.path("basics"); var ab = a.path("basics");
            check(id, "basics.name", same(ab.path("name").asText(), eb.path("name").asText()), eb.path("name").asText());
            check(id, "basics.phone", digits(ab.path("phone").asText()).endsWith(eb.path("phone").asText()), eb.path("phone").asText());
            check(id, "basics.email", same(ab.path("email").asText(), eb.path("email").asText()), eb.path("email").asText());
            check(id, "basics.city", norm(ab.path("city").asText()).contains(norm(eb.path("city").asText())), eb.path("city").asText());
            for (JsonNode link : eb.path("links"))
                check(id, "basics.links", strings(ab.path("links")).stream().anyMatch(l -> same(l, link.asText())), link.asText());
            section(id, "education", e, a, "school", List.of("degree", "major", "gpa"));
            section(id, "experience", e, a, "org", List.of("title"));
            section(id, "projects", e, a, "name", List.of("role"));
            var skills = new ArrayList<String>();
            a.path("skills").forEach(s -> skills.addAll(strings(s.path("items"))));
            for (JsonNode s : e.path("skills")) for (JsonNode item : s.path("items"))
                check(id, "skills.items", skills.stream().anyMatch(x -> same(x, item.asText())), item.asText());
            for (JsonNode cert : e.path("certificates"))
                check(id, "certificates", strings(a.path("certificates")).stream().anyMatch(x -> overlaps(x, cert.asText())), cert.asText());
            // Precision guard: awards or skills misfiled as certificates.
            for (String got : strings(a.path("certificates")))
                check(id, "certificates.precision", strings(e.path("certificates")).stream().anyMatch(x -> overlaps(got, x)), "not a stated certificate: " + got);
            if (!e.path("summary").asText().isBlank())
                check(id, "summary", overlaps(a.path("summary").asText(), e.path("summary").asText()), e.path("summary").asText());
        }

        private void section(String id, String name, JsonNode e, JsonNode a, String key, List<String> texts) {
            for (JsonNode want : e.path(name)) {
                JsonNode got = null;
                for (JsonNode candidate : a.path(name))
                    if (overlaps(candidate.path(key).asText(), want.path(key).asText())) { got = candidate; break; }
                check(id, name + "." + key, got != null, want.path(key).asText());
                JsonNode g = got == null ? JSON.createObjectNode() : got;
                for (String field : texts) if (!want.path(field).asText().isBlank())
                    check(id, name + "." + field, overlaps(g.path(field).asText(), want.path(field).asText()), want.path(field).asText());
                for (String field : List.of("start", "end"))
                    check(id, name + "." + field, Objects.equals(date(g.path(field).asText()), date(want.path(field).asText())), want.path(field).asText());
                for (String list : List.of("bullets", "tech")) for (JsonNode item : want.path(list)) {
                    var have = strings(g.path(list));
                    Function<String, Boolean> match = list.equals("tech") ? x -> same(x, item.asText()) : x -> overlaps(x, item.asText());
                    check(id, name + "." + list, have.stream().anyMatch(match::apply), item.asText());
                }
            }
        }

        static List<String> strings(JsonNode array) {
            var out = new ArrayList<String>();
            array.forEach(n -> out.add(n.asText()));
            return out;
        }
        static String norm(String s) {
            return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}，。；：、（）【】「」•·–—|]", "");
        }
        static boolean same(String a, String b) { return !norm(b).isEmpty() && norm(a).equals(norm(b)); }
        static boolean overlaps(String a, String b) {
            String x = norm(a), y = norm(b);
            return !x.isEmpty() && !y.isEmpty() && (x.contains(y) || y.contains(x));
        }
        static String digits(String s) { return s.replaceAll("\\D", "").replaceFirst("^86(?=1\\d{10}$)", ""); }
        static final List<String> MONTHS = List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec");
        /** yyyymm, "present", or the normalised text when no date is recognisable. */
        static String date(String s) {
            String t = s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
            if (t.matches(".*(至今|present|now|今).*")) return "present";
            var m = java.util.regex.Pattern.compile("(\\d{4})\\D{1,3}(\\d{1,2})").matcher(t);
            if (m.find()) return m.group(1) + String.format("%02d", Integer.parseInt(m.group(2)));
            for (int i = 0; i < 12; i++) {
                var w = java.util.regex.Pattern.compile(MONTHS.get(i) + "[a-z]*\\.?\\s*(\\d{4})").matcher(t);
                if (w.find()) return w.group(1) + String.format("%02d", i + 1);
            }
            return norm(t);
        }
    }
}
