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
        assertThatThrownBy(() -> reader.read(" ".getBytes(), "text/plain")).hasMessageContaining("没有提取到文本");
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
            assertThat(text.text()).contains("Jane Doe"); assertThat(text.images()).isEmpty();
            pdf.addPage(new PDPage()); out.reset(); pdf.save(out);
            var mixed = reader.read(out.toByteArray(), "application/pdf");
            assertThat(mixed.text()).contains("Jane Doe"); assertThat(mixed.images()).hasSize(1);
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
    private byte[] docx(String xml) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) { zip.putNextEntry(new ZipEntry("word/document.xml")); zip.write(xml.getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
        return out.toByteArray();
    }
}
