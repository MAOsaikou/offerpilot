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
                        // 同页可能同时包含文本层和扫描正文，始终保留页面视觉信息。
                        {
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
            if (mime.contains("wordprocessingml")) return docx(bytes);
            return checked(new String(bytes, StandardCharsets.UTF_8).replace("\uFEFF", ""), List.of(), "text");
        } catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw invalid("文件损坏、加密或格式无效，请重新导出后上传"); }
    }
    private Document docx(byte[] bytes) throws Exception {
        Map<String, byte[]> parts = new TreeMap<>();
        Map<String, byte[]> attachments = new HashMap<>();
        List<Image> images = new ArrayList<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            int count = 0, expanded = 0;
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++count > 500) throw invalid("DOCX 内容过多，请精简文件");
                byte[] data = zip.readNBytes(5 * 1024 * 1024 + 1);
                expanded += data.length;
                if (data.length > 5 * 1024 * 1024 || expanded > 20 * 1024 * 1024) throw invalid("DOCX 解压内容过大，请精简文件");
                String name = entry.getName();
                if (name.equals("word/document.xml") || name.matches("word/(header|footer)[0-9]+\\.xml")) {
                    if (parts.putIfAbsent(name, data) != null) throw invalid("DOCX 包含重复正文部件，请重新导出");
                }
                if ((name.startsWith("word/media/") || name.startsWith("word/_rels/")) && !entry.isDirectory())
                    if (attachments.putIfAbsent(name, data) != null) throw invalid("DOCX 包含重复部件，请重新导出");
            }
        }
        if (!parts.containsKey("word/document.xml")) throw invalid("这不是有效的 DOCX 文档");
        StringBuilder text = new StringBuilder();
        Set<String> usedImages = new LinkedHashSet<>();
        for (var part : parts.entrySet()) {
            var dom = xml(part.getValue());
            text.append(docxText(dom)).append('\n');
            var elements = dom.getElementsByTagName("*");
            Set<String> references = new LinkedHashSet<>();
            for (int i = 0; i < elements.getLength(); i++) {
                var element = (org.w3c.dom.Element) elements.item(i);
                if (!Set.of("blip", "imagedata").contains(element.getLocalName())) continue;
                String ns = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
                if (element.hasAttributeNS(ns, "link")) throw invalid("DOCX 含外链图片，请嵌入图片或导出 PDF");
                for (String attribute : List.of("embed", "id")) {
                    String reference = element.getAttributeNS(ns, attribute);
                    if (!reference.isBlank()) references.add(reference);
                }
            }
            if (references.isEmpty()) continue;
            byte[] relationships = attachments.get("word/_rels/" + part.getKey().substring(5) + ".rels");
            if (relationships == null) throw invalid("DOCX 图片引用缺失，请重新导出");
            var relations = xml(relationships).getElementsByTagNameNS("http://schemas.openxmlformats.org/package/2006/relationships", "Relationship");
            for (int i = 0; i < relations.getLength(); i++) {
                var relation = (org.w3c.dom.Element) relations.item(i);
                if (!references.remove(relation.getAttribute("Id"))) continue;
                if ("External".equals(relation.getAttribute("TargetMode"))) throw invalid("DOCX 含外链图片，请嵌入图片或导出 PDF");
                String target = relation.getAttribute("Target");
                String path = java.nio.file.Path.of("word").resolve(target).normalize().toString().replace('\\', '/');
                if (!path.startsWith("word/media/") || !attachments.containsKey(path)) throw invalid("DOCX 图片引用无效，请重新导出");
                usedImages.add(path);
            }
            if (!references.isEmpty()) throw invalid("DOCX 图片引用缺失，请重新导出");
        }
        for (String path : usedImages) {
            if (images.size() >= 20) throw invalid("DOCX 图片超过 20 张，请精简后上传");
            byte[] data = attachments.get(path);
            String imageMime;
            if (starts(data, new byte[]{(byte)137,80,78,71,13,10,26,10})) imageMime = "image/png";
            else if (starts(data, new byte[]{(byte)255,(byte)216,(byte)255})) imageMime = "image/jpeg";
            else throw invalid("DOCX 含暂不支持的嵌入媒体，请导出为 PDF 后上传");
            images.addAll(read(data, imageMime).images());
        }
        return checked(text.toString(), images, images.isEmpty() ? "docx-text" : "docx-vision");
    }
    private org.w3c.dom.Document xml(byte[] document) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(document));
    }
    private String docxText(org.w3c.dom.Document dom) {
        var paragraphs = dom.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "p");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < paragraphs.getLength(); i++) {
            var paragraph = (org.w3c.dom.Element) paragraphs.item(i);
            var runs = paragraph.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "t");
            for (int j = 0; j < runs.getLength(); j++) text.append(runs.item(j).getTextContent());
            text.append('\n');
        }
        return text.toString();
    }
    private Document checked(String text, List<Image> images, String channel) {
        if (text.length() > 50_000) throw invalid("简历文本超过 5 万字，请精简后上传");
        if (text.isBlank() && images.isEmpty()) throw invalid("没有提取到简历内容，请检查文件后重新上传");
        if (images.stream().mapToLong(image -> image.bytes().length).sum() > 24L * 1024 * 1024)
            throw invalid("简历图片内容过大，请压缩图片或减少页数后上传");
        return new Document(text.strip(), images, channel);
    }
    private boolean starts(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if (bytes[i] != prefix[i]) return false;
        return true;
    }
    private BusinessException invalid(String message) { return new BusinessException(ErrorCode.RESUME_INVALID, message); }
}
