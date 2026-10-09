package com.offerpilot.resume.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/** Fixed server-side classic template. All values are plain text from the saved schema. */
@Service
public class ResumePdfRenderer {
    private final String regularPath;
    private final String boldPath;
    private record Block(String text, float size, boolean bold, float indent, float gap, boolean heading) {}

    public ResumePdfRenderer(@Value("${offerpilot.resume.export.font-regular:}") String regularPath,
                             @Value("${offerpilot.resume.export.font-bold:}") String boldPath) {
        this.regularPath = regularPath;
        this.boldPath = boldPath;
    }

    public static void validateTemplate(String template) {
        if (!"classic".equals(template)) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不支持的 PDF 模板，请使用 classic");
    }

    public byte[] render(String title, JsonNode structured, String template) {
        validateTemplate(template);
        ResumeStructure.validate(structured);
        List<Block> blocks = template(title, structured);
        try (PDDocument document = new PDDocument();
             ResumePdfFont regular = ResumePdfFont.open(regularPath, false);
             ResumePdfFont bold = ResumePdfFont.open(boldPath, true)) {
            document.getDocumentInformation().setTitle(title);
            document.getDocumentInformation().setCreator("OfferPilot");
            regular.embed(document, blocks.stream().filter(block -> !block.bold()).map(Block::text).toList());
            bold.embed(document, blocks.stream().filter(Block::bold).map(Block::text).toList());
            try (Layout layout = new Layout(document, regular, bold)) {
                for (Block block : blocks) layout.draw(block);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.RESUME_EXPORT_UNAVAILABLE, "PDF 导出失败，请检查中文字体配置后重试");
        }
    }

    private static List<Block> template(String title, JsonNode root) {
        List<Block> blocks = new ArrayList<>();
        JsonNode basics = root.path("basics");
        String name = text(basics, "name");
        add(blocks, name.isBlank() ? title : name, 22, true, 0, 8, false);
        add(blocks, join(text(basics, "phone"), text(basics, "email"), text(basics, "city")), 10, false, 0, 3, false);
        for (JsonNode link : basics.path("links")) body(blocks, link.asText());
        section(blocks, "个人摘要", List.of(new Block(clean(root.path("summary").asText()), 11, false, 0, 3, false)));
        List<Block> entries = new ArrayList<>();
        for (JsonNode item : root.path("education")) {
            entry(entries, join(text(item, "school"), text(item, "degree"), text(item, "major"), dates(item)));
            String gpa = text(item, "gpa");
            if (!gpa.isBlank()) body(entries, "GPA / 成绩：" + gpa);
        }
        section(blocks, "教育经历", entries);
        entries = new ArrayList<>();
        for (JsonNode item : root.path("experience")) {
            entry(entries, join(text(item, "org"), text(item, "title"), dates(item)));
            bullets(entries, item.path("bullets"));
        }
        section(blocks, "工作经历", entries);
        entries = new ArrayList<>();
        for (JsonNode item : root.path("projects")) {
            entry(entries, join(text(item, "name"), text(item, "role"), dates(item)));
            String tech = strings(item.path("tech"));
            if (!tech.isBlank()) body(entries, "技术栈：" + tech);
            bullets(entries, item.path("bullets"));
        }
        section(blocks, "项目经历", entries);
        entries = new ArrayList<>();
        for (JsonNode item : root.path("skills")) {
            String category = text(item, "category"), items = strings(item.path("items"));
            body(entries, category.isBlank() ? items : category + (items.isBlank() ? "" : "：" + items));
        }
        section(blocks, "专业技能", entries);
        entries = new ArrayList<>();
        for (JsonNode certificate : root.path("certificates")) body(entries, certificate.asText());
        section(blocks, "证书", entries);
        return blocks;
    }
    private static void section(List<Block> blocks, String label, List<Block> entries) {
        List<Block> visible = entries.stream().filter(block -> !block.text().isBlank()).toList();
        if (visible.isEmpty()) return;
        add(blocks, label, 13, true, 0, 6, true);
        blocks.addAll(visible);
    }
    private static void entry(List<Block> blocks, String value) { add(blocks, value, 11, true, 0, 4, false); }
    private static void body(List<Block> blocks, String value) { add(blocks, value, 11, false, 0, 3, false); }
    private static void bullets(List<Block> blocks, JsonNode array) {
        for (JsonNode item : array) if (!item.asText().isBlank()) add(blocks, "• " + item.asText(), 11, false, 10, 3, false);
    }
    private static void add(List<Block> blocks, String text, float size, boolean bold, float indent, float gap, boolean heading) {
        String value = clean(text);
        if (!value.isBlank()) blocks.add(new Block(value, size, bold, indent, gap, heading));
    }
    private static String dates(JsonNode item) {
        return java.util.stream.Stream.of(text(item, "start"), text(item, "end")).filter(value -> !value.isBlank()).collect(Collectors.joining(" – "));
    }
    private static String join(String... values) {
        return Arrays.stream(values).filter(value -> !value.isBlank()).collect(Collectors.joining(" · "));
    }
    private static String strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(clean(value.asText())));
        return join(values.toArray(String[]::new));
    }
    private static String text(JsonNode node, String key) { return clean(node.path(key).asText()); }
    private static String clean(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ')
            .replaceAll("[\\p{Cntrl}&&[^\\n]]", "").strip();
    }

    private static final class Layout implements AutoCloseable {
        private static final float MARGIN = 48;
        private final PDDocument document;
        private final ResumePdfFont regular, bold;
        private PDPageContentStream content;
        private float y;
        Layout(PDDocument document, ResumePdfFont regular, ResumePdfFont bold) throws IOException {
            this.document = document; this.regular = regular; this.bold = bold;
            page();
        }
        private void page() throws IOException {
            if (content != null) content.close();
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            content = new PDPageContentStream(document, page);
            y = PDRectangle.A4.getHeight() - MARGIN;
        }
        void draw(Block block) throws IOException {
            ResumePdfFont font = block.bold() ? bold : regular;
            List<String> lines = wrap(block.text(), font, block.size(), PDRectangle.A4.getWidth() - 2 * MARGIN - block.indent());
            float leading = block.size() * 1.5f;
            // Keep section titles with at least the first content line on the same page.
            float reserve = block.heading() ? leading + 36 : leading;
            if (y - reserve < MARGIN) page();
            if (block.heading()) {
                y -= 10;
                content.setStrokingColor(.7f);
                content.setLineWidth(.5f);
                content.moveTo(MARGIN, y - leading + 3);
                content.lineTo(PDRectangle.A4.getWidth() - MARGIN, y - leading + 3);
                content.stroke();
            }
            for (String line : lines) {
                if (y - leading < MARGIN) page();
                y -= leading;
                content.beginText();
                content.newLineAtOffset(MARGIN + block.indent(), y);
                font.show(content, line, block.size());
                content.endText();
            }
            y -= block.gap();
        }
        private static List<String> wrap(String text, ResumePdfFont font, float size, float maxWidth) {
            List<String> lines = new ArrayList<>();
            for (String paragraph : text.split("\n", -1)) {
                StringBuilder line = new StringBuilder();
                float width = 0;
                for (int point : paragraph.codePoints().toArray()) {
                    float advance = font.width(point, size);
                    if (width + advance > maxWidth && !line.isEmpty()) {
                        lines.add(line.toString()); line.setLength(0); width = 0;
                    }
                    line.appendCodePoint(point); width += advance;
                }
                lines.add(line.toString());
            }
            return lines;
        }
        @Override public void close() throws IOException { if (content != null) content.close(); }
    }
}
