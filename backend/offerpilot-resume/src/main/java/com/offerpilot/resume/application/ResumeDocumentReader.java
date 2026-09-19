package com.offerpilot.resume.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipInputStream;

@Component
public class ResumeDocumentReader {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public record Image(byte[] bytes, String mime) {}
    public record Document(String text, List<Image> images, String channel) {}
    public String mime(String filename, byte[] bytes) {
        if (bytes.length == 0) throw invalid("文件为空，请选择包含简历内容的文件");
        if (bytes.length > MAX_BYTES) throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "简历文件不能超过 10MB");
        String name = filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf") && starts(bytes, "%PDF-".getBytes(StandardCharsets.US_ASCII))) return "application/pdf";
        if (name.endsWith(".docx") && starts(bytes, new byte[]{80,75,3,4})) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (name.endsWith(".png") && starts(bytes, new byte[]{(byte)137,80,78,71,13,10,26,10})) return "image/png";
        if ((name.endsWith(".jpg") || name.endsWith(".jpeg")) && starts(bytes, new byte[]{(byte)255,(byte)216,(byte)255})) return "image/jpeg";
        if (name.endsWith(".txt") || name.endsWith(".md")) {
            try {
                String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
                if (text.indexOf('\0') >= 0) throw invalid("文本文件必须是 UTF-8 编码");
                return "text/plain";
            } catch (java.nio.charset.CharacterCodingException ex) { throw invalid("文本文件必须是 UTF-8 编码"); }
        }
        throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED, "请选择 PDF、DOCX、PNG、JPG 或 UTF-8 TXT/Markdown，文件内容须与扩展名一致");
    }
    public Document read(byte[] bytes, String mime) {
        try {
            if (mime.equals("application/pdf")) {
                try (var pdf = Loader.loadPDF(bytes)) {
                    if (pdf.isEncrypted()) throw invalid("请先解除 PDF 密码保护，再上传");
                    if (pdf.getNumberOfPages() < 1 || pdf.getNumberOfPages() > 10) throw invalid("PDF 简历须为 1–10 页");
                    List<Image> images = new ArrayList<>();
                    StringBuilder text = new StringBuilder();
                    var stripper = new PDFTextStripper();
                    stripper.setSortByPosition(true);
                    var renderer = new PDFRenderer(pdf);
                    for (int i = 0; i < pdf.getNumberOfPages(); i++) {
                        stripper.setStartPage(i + 1); stripper.setEndPage(i + 1);
                        String page = stripper.getText(pdf);
                        text.append(page).append('\n');
                        if (page.replaceAll("\\s", "").length() < 30) {
                            var box = pdf.getPage(i).getCropBox();
                            if (box.getWidth() <= 0 || box.getHeight() <= 0 || box.getWidth() > 2000 || box.getHeight() > 2000)
                                throw invalid("PDF 页面尺寸过大或无效，请转换为标准 A4 页面");
                            float dpi = Math.min(130, 1600 * 72 / Math.max(box.getWidth(), box.getHeight()));
                            var image = renderer.renderImageWithDPI(i, dpi);
                            var out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); image.flush();
                            images.add(new Image(out.toByteArray(), "image/png"));
                        }
                    }
                    return checked(text.toString(), images, images.isEmpty() ? "pdf-text" : "pdf-vision");
                }
            }
            if (mime.startsWith("image/")) {
                try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                    var readers = ImageIO.getImageReaders(input);
                    if (!readers.hasNext()) throw invalid("图片无法读取，请重新导出 PNG/JPG");
                    var reader = readers.next();
                    try {
                        reader.setInput(input);
                        long width = reader.getWidth(0), height = reader.getHeight(0);
                        if (width < 1 || height < 1 || width * height > 20_000_000 || width > 10000 || height > 10000)
                            throw invalid("图片尺寸过大，请缩小到 2000 万像素以内");
                        var image = reader.read(0);
                        if (image == null) throw invalid("图片损坏，请重新导出");
                        image.flush();
                    } finally { reader.dispose(); }
                }
                return new Document("", List.of(new Image(bytes, mime)), "image-vision");
            }
            if (mime.contains("wordprocessingml")) return checked(docx(bytes), List.of(), "docx-text");
            return checked(new String(bytes, StandardCharsets.UTF_8).replace("\uFEFF", ""), List.of(), "text");
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw invalid("文件损坏、加密或格式无效，请重新导出后上传"); }
    }
    private String docx(byte[] bytes) throws Exception {
        byte[] document = null;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            int count = 0, expanded = 0;
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++count > 500) throw invalid("DOCX 内容过多，请精简文件");
                byte[] data = zip.readNBytes(5 * 1024 * 1024 + 1);
                expanded += data.length;
                if (data.length > 5 * 1024 * 1024 || expanded > 20 * 1024 * 1024) throw invalid("DOCX 解压内容过大，请精简文件");
                if (entry.getName().equals("word/document.xml")) document = data;
            }
        }
        if (document == null) throw invalid("这不是有效的 DOCX 文档");
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        var dom = factory.newDocumentBuilder().parse(new ByteArrayInputStream(document));
        var paragraphs = dom.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "p");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < paragraphs.getLength(); i++) {
            text.append(paragraphs.item(i).getTextContent()).append('\n');
        }
        return text.toString();
    }
    private Document checked(String text, List<Image> images, String channel) {
        if (text.length() > 50_000) throw invalid("简历文本超过 5 万字，请精简后上传");
        if (text.isBlank() && images.isEmpty()) throw invalid("没有提取到文本。图片型 DOCX 请先导出为 PDF 或图片再上传");
        return new Document(text.strip(), images, channel);
    }
    private boolean starts(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (bytes[i] != prefix[i]) return false;
        return true;
    }
    private BusinessException invalid(String message) { return new BusinessException(ErrorCode.RESUME_INVALID, message); }
}
