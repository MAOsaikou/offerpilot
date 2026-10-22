package com.offerpilot.resume.application;

import org.junit.jupiter.api.Test;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.assertj.core.api.Assertions.*;

class ResumeDocumentReaderTest {
    private final ResumeDocumentReader reader = new ResumeDocumentReader();
    @Test void validatesContentAndLimitsBeforeStorage() {
        assertThatThrownBy(() -> reader.mime("resume.pdf", "fake".getBytes())).hasMessageContaining("文件内容");
        assertThatThrownBy(() -> reader.mime("resume.txt", new byte[0])).hasMessageContaining("为空");
        assertThatThrownBy(() -> reader.mime("resume.txt", new byte[ResumeDocumentReader.MAX_BYTES + 1])).hasMessageContaining("10MB");
        assertThatThrownBy(() -> reader.mime("resume.md", new byte[]{(byte)0xff})).hasMessageContaining("UTF-8");
        assertThatThrownBy(() -> reader.read(" ".getBytes(), "text/plain")).hasMessageContaining("没有提取到简历内容");
    }
    @Test void readsDocxParagraphsAndRefusesXmlEntities() throws Exception {
        String xml = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>张同学</w:t></w:r></w:p><w:p><w:r><w:t>软件工程</w:t></w:r></w:p></w:body></w:document>";
        var bytes = docx(xml);
        assertThat(reader.read(bytes, reader.mime("resume.docx", bytes)).text()).isEqualTo("张同学\n软件工程");
        assertThatThrownBy(() -> reader.read(docx("<!DOCTYPE x [<!ENTITY secret SYSTEM 'file:///secret'>]><x>&secret;</x>"), "application/vnd.openxmlformats-officedocument.wordprocessingml.document")).hasMessageContaining("格式无效");
    }
    @Test void readsPdfTextAndRoutesScannedPagesToVision() throws Exception {
        try (var pdf = new PDDocument()) {
            var first = new PDPage(); pdf.addPage(first);
            try (var stream = new PDPageContentStream(pdf, first)) {
                stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(40, 700); stream.showText("Jane Doe - Software Engineering - Java Spring Boot"); stream.endText();
            }
            var out = new ByteArrayOutputStream(); pdf.save(out);
            var text = reader.read(out.toByteArray(), "application/pdf");
            assertThat(text.text()).contains("Jane Doe"); assertThat(text.images()).hasSize(1);
            pdf.addPage(new PDPage()); out.reset(); pdf.save(out);
            var mixed = reader.read(out.toByteArray(), "application/pdf");
            assertThat(mixed.text()).contains("Jane Doe"); assertThat(mixed.images()).hasSize(2);
            for (int i = 0; i < 9; i++) pdf.addPage(new PDPage());
            out.reset(); pdf.save(out);
            assertThatThrownBy(() -> reader.read(out.toByteArray(), "application/pdf")).hasMessageContaining("1–10 页");
        }
    }
    @Test void refusesOversizedTextAndZipExpansion() throws Exception {
        assertThatThrownBy(() -> reader.read("x".repeat(50_001).getBytes(), "text/plain")).hasMessageContaining("5 万字");
        byte[] bomb = docx("x".repeat(5 * 1024 * 1024 + 1));
        assertThatThrownBy(() -> reader.read(bomb, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")).hasMessageContaining("解压内容过大");
    }
    @Test void readsDocxHeadersFootersAndEmbeddedImages() throws Exception {
        var picture = new java.awt.image.BufferedImage(100, 100, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var png = new ByteArrayOutputStream(); javax.imageio.ImageIO.write(picture, "png", png);
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (var part : java.util.Map.of("word/document.xml", "项目经历", "word/header1.xml", "contact@example.com", "word/footer1.xml", "证书").entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                String drawing = part.getKey().equals("word/document.xml") ? "<a:blip xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" r:embed=\"image1\"/>" : "";
                zip.write(("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:p><w:r><w:t>" + part.getValue() + "</w:t>" + drawing + "</w:r></w:p></w:document>").getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("word/media/image1.png")); zip.write(png.toByteArray()); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/media/unused-old-image.png")); zip.write(png.toByteArray()); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/_rels/document.xml.rels"));
            zip.write("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"image1\" Target=\"media/image1.png\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\"/></Relationships>".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        var result = reader.read(out.toByteArray(), "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(result.text()).contains("项目经历", "contact@example.com", "证书");
        assertThat(result.images()).hasSize(1); assertThat(result.channel()).isEqualTo("docx-vision");
    }
    @Test void preservesPictureOnPdfPageWithLongTextLayer() throws Exception {
        try (var pdf = new PDDocument()) {
            var page = new PDPage(); pdf.addPage(page);
            var picture = new java.awt.image.BufferedImage(100, 100, java.awt.image.BufferedImage.TYPE_INT_RGB);
            try (var stream = new PDPageContentStream(pdf, page)) {
                stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(40, 700); stream.showText("This text layer exceeds thirty characters but the resume is an image."); stream.endText();
                stream.drawImage(org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(pdf, picture), 40, 200, 300, 300);
            }
            var out = new ByteArrayOutputStream(); pdf.save(out);
            var result = reader.read(out.toByteArray(), "application/pdf");
            assertThat(result.text()).contains("thirty"); assertThat(result.images()).hasSize(1);
            assertThat(result.images().get(0).bytes().length).isPositive();
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"png", "jpg", "jpeg"})
    void readsRasterFormatsAndRejectsTruncatedOrMislabeledContent(String format) throws Exception {
        var picture = new java.awt.image.BufferedImage(200, 300, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(picture, format.equals("jpeg") ? "jpg" : format, out);
        byte[] bytes = out.toByteArray();
        String mime = reader.mime("fictional." + format, bytes);
        var document = reader.read(bytes, mime);
        assertThat(document.channel()).isEqualTo("image-vision");
        assertThat(document.text()).isEmpty();
        assertThat(document.images()).hasSize(1);
        assertThat(document.images().get(0).bytes()).isEqualTo(bytes);
        assertThatThrownBy(() -> reader.mime("fictional.pdf", bytes)).hasMessageContaining("文件内容");
        assertThatThrownBy(() -> reader.read(java.util.Arrays.copyOf(bytes, 12), mime)).isInstanceOf(com.offerpilot.common.error.BusinessException.class);
    }
    private byte[] docx(String xml) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) { zip.putNextEntry(new ZipEntry("word/document.xml")); zip.write(xml.getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
        return out.toByteArray();
    }
}
