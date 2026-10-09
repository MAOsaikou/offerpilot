package com.offerpilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.config.OfferPilotProperties;
import com.offerpilot.llm.ModelGateway;
import com.offerpilot.resume.application.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;

/** 显式启用才调用付费模型；仅发送本测试生成的虚构简历，不连接业务数据库。 */
@EnabledIfEnvironmentVariable(named = "OFFERPILOT_RUN_LIVE_RESUME", matches = "true")
class ResumeLiveEvaluationTest {
    @Test void extractsKnownFactsFromTextImageMixedPdfAndDocx() throws Exception {
        String key = System.getenv("DEEPSEEK_API_KEY");
        assertThat(key).as("设置 DEEPSEEK_API_KEY 后再启用评测").isNotBlank();
        String base = System.getenv().getOrDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com");
        var tier = new OfferPilotProperties.Llm.Tier("deepseek", "deepseek-flash", Duration.ofSeconds(90), true);
        var properties = new OfferPilotProperties(null, null, null, null, null,
                new OfferPilotProperties.Llm("CHEAP", Map.of("CHEAP", tier, "VISION_ALT", tier), Map.of()), null, null);
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("deepseek", DeepSeekChatModel.builder().deepSeekApi(DeepSeekApi.builder().baseUrl(base).apiKey(key).build()).build());
        var environment = new MockEnvironment().withProperty("spring.ai.deepseek.api-key", key).withProperty("spring.ai.deepseek.base-url", base);
        var gateway = new ModelGateway(properties, beans.getBeanProvider(DeepSeekChatModel.class), beans.getBeanProvider(OpenAiChatModel.class), environment);
        var json = new ObjectMapper();
        var extractor = new ResumeExtractor(gateway, json, new ClassPathResource("prompts/resume/extract.st"));
        var reader = new ResumeDocumentReader();
        String text = "林知远\nlin.zhiyuan@example.com\n教育：星海大学，本科，软件工程，2022.09 至 2026.06\n项目：校园图书管理系统\n角色：后端开发\n技术：Java、Spring Boot\n职责：实现图书借阅接口\n证书：大学英语六级\n";
        var picture = new BufferedImage(1400, 900, BufferedImage.TYPE_INT_RGB);
        var graphics = picture.createGraphics();
        graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 1400, 900); graphics.setColor(Color.BLACK);
        graphics.setFont(new Font("Dialog", Font.PLAIN, 38));
        int y = 70;
        for (String line : text.split("\n")) { graphics.drawString(line, 40, y); y += 75; }
        graphics.dispose();
        var png = new ByteArrayOutputStream(); javax.imageio.ImageIO.write(picture, "png", png);
        var pdfBytes = new ByteArrayOutputStream();
        try (var pdf = new PDDocument()) {
            var page = new PDPage(); pdf.addPage(page);
            try (var content = new PDPageContentStream(pdf, page)) {
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(30, 760); content.showText("Resume: image body below this long selectable text heading."); content.endText();
                content.drawImage(LosslessFactory.createFromImage(pdf, picture), 20, 330, 570, 367);
            }
            pdf.save(pdfBytes);
        }
        var docx = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(docx)) {
            String ns = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";
            for (var entry : Map.of("word/document.xml", "<w:document " + ns + "><w:body><w:p><w:r><w:drawing><a:blip xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" r:embed=\"image1\"/></w:drawing></w:r></w:p></w:body></w:document>",
                    "word/header1.xml", "<w:hdr " + ns + "><w:p><w:r><w:t>lin.zhiyuan@example.com</w:t></w:r></w:p></w:hdr>").entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey())); zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("word/media/image1.png")); zip.write(png.toByteArray()); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/_rels/document.xml.rels"));
            zip.write("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"image1\" Target=\"media/image1.png\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\"/></Relationships>".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        var cases = new java.util.LinkedHashMap<String, byte[]>();
        cases.put("sample.txt", text.getBytes(StandardCharsets.UTF_8)); cases.put("sample.png", png.toByteArray());
        cases.put("mixed.pdf", pdfBytes.toByteArray()); cases.put("image.docx", docx.toByteArray());
        var reports = json.createArrayNode();
        var assertions = new org.assertj.core.api.SoftAssertions();
        for (var entry : cases.entrySet()) {
            try {
            var result = extractor.extractResult(reader.read(entry.getValue(), reader.mime(entry.getKey(), entry.getValue())));
            var actual = result.structured();
            assertions.assertThat(actual.at("/basics/name").asText()).as(entry.getKey() + " 姓名").isEqualTo("林知远");
            assertions.assertThat(actual.at("/basics/email").asText()).as(entry.getKey() + " 邮箱").isEqualTo("lin.zhiyuan@example.com");
            assertions.assertThat(actual.at("/education/0/school").asText()).as(entry.getKey() + " 学校").isEqualTo("星海大学");
            assertions.assertThat(actual.at("/education/0/major").asText()).as(entry.getKey() + " 专业").isEqualTo("软件工程");
            assertions.assertThat(actual.at("/projects/0/name").asText()).as(entry.getKey() + " 项目").isEqualTo("校园图书管理系统");
            assertions.assertThat(actual.path("certificates").toString()).as(entry.getKey() + " 证书").contains("大学英语六级");
            assertions.assertThat(actual.at("/basics/phone").asText()).as(entry.getKey() + " 不编造电话").isEmpty();
            assertions.assertThat(actual.path("experience").size()).as(entry.getKey() + " 不编造实习").isZero();
            assertions.assertThat(actual.path("projects").size()).as(entry.getKey() + " 项目去重").isEqualTo(1);
            reports.addObject().put("case", entry.getKey()).set("result", json.valueToTree(result));
            } catch (com.offerpilot.common.error.BusinessException ex) {
                reports.addObject().put("case", entry.getKey()).put("errorCode", ex.errorCode().code()).put("error", ex.getMessage());
                assertions.fail(entry.getKey() + "：" + ex.getMessage());
            }
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target/resume-live-evaluation.json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(reports));
        }
        assertions.assertAll();
    }
}
