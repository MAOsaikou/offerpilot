package com.offerpilot.common.document;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.encryption.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

class DocumentTextReaderTest {
    private final DocumentTextReader reader = new DocumentTextReader();
    @Test void utf8AndTextLayerPdfAreReadWithoutRendering() throws Exception {
        assertThat(reader.read("资料.MD", "text/plain;charset=UTF-8", "\uFEFF中文😀\n经验".getBytes(StandardCharsets.UTF_8)).content())
                .isEqualTo("中文😀\n经验");
        assertThat(reader.read("notes.pdf", "application/pdf", pdf(1, true, null)).content()).contains("Redis project");
        assertThat(reader.read("notes.txt", "", "文".repeat(30001).getBytes(StandardCharsets.UTF_8)).content()).hasSize(30001);
    }
    @Test void rejectsInvalidTypesEncodingEmptyAndOversizeBeforeExtraction() {
        assertThatThrownBy(() -> reader.read("x.pdf", null, new byte[0])).hasMessageContaining("为空");
        assertThatThrownBy(() -> reader.read("x.txt", null, new byte[DocumentTextReader.MAX_BYTES+1])).hasMessageContaining("10MB");
        assertThatThrownBy(() -> reader.read("x.docx", null, "PK".getBytes())).hasMessageContaining("仅支持");
        assertThatThrownBy(() -> reader.read("x.pdf", null, "text".getBytes())).hasMessageContaining("不一致");
        assertThatThrownBy(() -> reader.read("x.txt", null, "%PDF-1.7".getBytes())).hasMessageContaining("不一致");
        assertThatThrownBy(() -> reader.read("x.md", "image/png", "text".getBytes())).hasMessageContaining("不一致");
        assertThatThrownBy(() -> reader.read("x.txt", null, new byte[]{(byte)0xff})).hasMessageContaining("UTF-8");
        assertThatThrownBy(() -> reader.read("x.txt", null, new byte[]{0})).hasMessageContaining("空字符");
        assertThatThrownBy(() -> reader.read("x.txt", null, " \n\t".getBytes())).hasMessageContaining("没有提取到正文");
    }
    @Test void encryptedScannedBrokenAndTooManyPagesHaveSafeErrors() throws Exception {
        for (String password : new String[]{"secret", ""}) {
            byte[] encrypted = pdf(1, true, password);
            assertThatThrownBy(() -> reader.read("x.pdf", null, encrypted)).hasMessageContaining("解除 PDF 密码保护");
        }
        byte[] scanned = pdf(1, false, null), many = pdf(11, true, null);
        assertThatThrownBy(() -> reader.read("x.pdf", null, scanned)).hasMessageContaining("暂不支持图片 OCR");
        assertThatThrownBy(() -> reader.read("x.pdf", null, many)).hasMessageContaining("1–10 页");
        assertThatThrownBy(() -> reader.read("x.pdf", null, "%PDF-broken".getBytes())).hasMessageContaining("损坏");
    }
    private byte[] pdf(int pages, boolean text, String password) throws Exception {
        try (var pdf = new PDDocument(); var out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                var page = new PDPage(); pdf.addPage(page);
                if (text) try (var content = new PDPageContentStream(pdf, page)) {
                    content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 750); content.showText("Redis project"); content.endText();
                }
            }
            if (password != null) pdf.protect(new StandardProtectionPolicy("owner", password, new AccessPermission()));
            pdf.save(out); return out.toByteArray();
        }
    }
}
