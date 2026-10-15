package com.offerpilot.resume.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class ResumePdfRendererTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ResumePdfRenderer renderer = new ResumePdfRenderer("", "");
    @TempDir Path temp;

    ObjectNode fixture() throws Exception {
        ObjectNode root = (ObjectNode) json.readTree(ResumeStructure.SCHEMA);
        ((ObjectNode) root.path("basics")).put("name", "林小雨").put("phone", "13800000000")
            .put("email", "lin@example.com").put("city", "杭州").withArray("links").add("https://example.com");
        ((ObjectNode) root.path("education").get(0)).put("school", "浙江大学").put("degree", "本科")
            .put("major", "计算机科学").put("start", "2020-09").put("end", "2024-06").put("gpa", "3.8/4.0");
        ((ObjectNode) root.path("experience").get(0)).put("org", "示例公司").put("title", "开发工程师")
            .put("start", "2024-07").put("end", "至今").withArray("bullets").add("实现中文检索接口，提升查询效率");
        ((ObjectNode) root.path("projects").get(0)).put("name", "校园服务平台").put("role", "负责人")
            .put("start", "2023").put("end", "2024").withArray("tech").add("Java");
        ((ObjectNode) root.path("projects").get(0)).withArray("bullets").add("交付项目成果");
        ((ObjectNode) root.path("skills").get(0)).put("category", "后端").withArray("items").add("Spring Boot");
        root.withArray("certificates").add("英语六级");
        root.put("summary", "热爱软件开发。\n<script>alert('原样文本')</script>");
        return root;
    }

    @Test void exportsChineseAllSchemaFieldsAndPortableVectorGlyphs() throws Exception {
        byte[] pdf = renderer.render("技术简历", fixture(), "classic");
        assertThat(new String(pdf, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        try (var document = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(text).contains("林小雨", "浙江大学", "实现中文检索接口，提升查询效率", "13800000000", "lin@example.com", "杭州",
                "https://example.com", "本科", "计算机科学", "2020-09", "2024-06", "3.8/4.0", "示例公司", "开发工程师", "至今",
                "校园服务平台", "负责人", "Java", "交付项目成果", "后端", "Spring Boot", "英语六级", "个人摘要", "教育经历", "工作经历", "项目经历", "专业技能", "证书",
                "<script>alert('原样文本')</script>");
            assertThat(document.getPage(0).getMediaBox().getWidth()).isEqualTo(PDRectangle.A4.getWidth());
            assertThat(document.getPage(0).getMediaBox().getHeight()).isEqualTo(PDRectangle.A4.getHeight());
            assertThat(document.getPage(0).getResources().getFontNames()).isNotEmpty();
            // Exercise actual glyph programs as well as extraction: no system-font substitution.
            var image = new PDFRenderer(document).renderImage(0);
            assertThat(image.getWidth()).isGreaterThan(500);
        }
        assertThat(pdf.length).isLessThan(1_000_000);
        // Local review artifact only, never a committed font or PDF binary.
        Files.write(temp.resolve("resume.pdf"), pdf);
    }


    @Test void compactTemplateKeepsAllFieldsReordersSkillsAndUsesFewerPages() throws Exception {
        var root = fixture();
        var bullets = ((ObjectNode) root.path("experience").get(0)).withArray("bullets");
        for (int i = 0; i < 12; i++) bullets.add("第" + i + "项成果：" + "完成接口开发与压测验证。".repeat(4));
        byte[] compact = renderer.render("技术简历", root, "compact");
        byte[] classic = renderer.render("技术简历", root, "classic");
        try (var c = Loader.loadPDF(compact); var k = Loader.loadPDF(classic)) {
            String text = new PDFTextStripper().getText(c);
            assertThat(text).contains("林小雨", "https://example.com", "浙江大学", "3.8/4.0", "示例公司", "第11项成果",
                "校园服务平台", "Spring Boot", "英语六级", "<script>alert('原样文本')</script>");
            assertThat(text.indexOf("专业技能")).isLessThan(text.indexOf("教育经历"));
            assertThat(c.getNumberOfPages()).as("compact %d vs classic %d", c.getNumberOfPages(), k.getNumberOfPages()).isEqualTo(1);
            assertThat(k.getNumberOfPages()).isGreaterThan(1);
        }
        Files.write(temp.resolve("resume-compact.pdf"), compact);
    }

    @Test void wrapsLongContentAndKeepsLastBulletOnLaterPage() throws Exception {
        var root = fixture();
        var bullets = ((ObjectNode) root.path("experience").get(0)).withArray("bullets");
        for (int i = 0; i < 90; i++) bullets.add("第" + i + "项工作成果：" + "参与开发并完成验证。".repeat(25));
        bullets.add("最后一条完整成果");
        // >255 distinct glyphs exercises Type 3 font switching and Unicode mapping.
        root.put("summary", java.util.stream.IntStream.range(0x4e00, 0x4f40)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString());
        byte[] pdf = renderer.render("长简历", root, "classic");
        try (var document = Loader.loadPDF(pdf)) {
            assertThat(document.getNumberOfPages()).isGreaterThan(2);
            assertThat(new PDFTextStripper().getText(document)).contains("最后一条完整成果", "一丁丂七");
            PDFTextStripper last = new PDFTextStripper();
            last.setStartPage(document.getNumberOfPages());
            assertThat(last.getText(document)).contains("英语六级");
        }
    }

    @Test void rejectsInvalidTemplateAndDiagnosesMissingOrNonCjkFonts() throws Exception {
        assertThatThrownBy(() -> renderer.render("简历", fixture(), "modern"))
            .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        var missing = new ResumePdfRenderer(temp.resolve("absent.ttc").toString(), "");
        assertThatThrownBy(() -> missing.render("简历", fixture(), "classic"))
            .isInstanceOfSatisfying(BusinessException.class, ex -> {
                assertThat(ex.errorCode()).isEqualTo(ErrorCode.RESUME_EXPORT_UNAVAILABLE);
                assertThat(ex.getMessage()).contains("font-regular");
            });
        Path invalid = Files.writeString(temp.resolve("invalid.ttf"), "not a font");
        assertThatThrownBy(() -> new ResumePdfRenderer(invalid.toString(), "").render("简历", fixture(), "classic"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("中文字体");
    }
}
