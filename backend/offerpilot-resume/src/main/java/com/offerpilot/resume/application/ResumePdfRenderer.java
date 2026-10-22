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

/**
 * Fixed server-side templates (RS-07). All values are plain text from the saved schema.
 * <ul>
 *   <li>{@code classic}: roomy single column, underlined section titles, education before experience.</li>
 *   <li>{@code compact}: denser campus layout aimed at one A4 page; skills right after the summary,
 *       accent bar section titles, smaller type and margins.</li>
 * </ul>
 */
@Service
public class ResumePdfRenderer {
    private final String regularPath;
    private final String boldPath;
    private record Block(String text, float size, boolean bold, float indent, float gap, boolean heading) {}
    public static final List<String> TEMPLATES = List.of("classic", "compact");
    /** Type sizes and spacing per template; {@code scale} multiplies body sizes and gaps. */
    private record Style(boolean compact, float margin, float nameSize, float headingSize, float bodySize, float scale) {
        static Style of(String template) {
            return "compact".equals(template) ? new Style(true, 36, 18, 11.5f, 9.5f, .75f) : new Style(false, 48, 22, 13, 11, 1f);
        }
    }

    public ResumePdfRenderer(@Value("${offerpilot.resume.export.font-regular:}") String regularPath,
                             @Value("${offerpilot.resume.export.font-bold:}") String boldPath) {
        this.regularPath = regularPath;
        this.boldPath = boldPath;
    }

    public static void validateTemplate(String template) {
        if (!TEMPLATES.contains(template))
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "不支持的 PDF 模板，请使用 classic 或 compact");
    }

    public byte[] render(String title, JsonNode structured, String template) {
        validateTemplate(template);
        ResumeStructure.validate(structured);
        Style style = Style.of(template);
        List<Block> blocks = template(title, structured, style);
        try (PDDocument document = new PDDocument();
             ResumePdfFont regular = ResumePdfFont.open(regularPath, false);
             ResumePdfFont bold = ResumePdfFont.open(boldPath, true)) {
            document.getDocumentInformation().setTitle(title);
            document.getDocumentInformation().setCreator("OfferPilot");
            regular.embed(document, blocks.stream().filter(block -> !block.bold()).map(Block::text).toList());
            bold.embed(document, blocks.stream().filter(Block::bold).map(Block::text).toList());
            try (Layout layout = new Layout(document, regular, bold, style)) {
                for (Block block : blocks) layout.draw(block);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            return output.toByteArray();
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.RESUME_EXPORT_UNAVAILABLE, "PDF 导出失败，请检查中文字体配置后重试");
        }
    }

    private static List<Block> template(String title, JsonNode root, Style style) {
        List<Block> blocks = new ArrayList<>();
        JsonNode basics = root.path("basics");
        String name = text(basics, "name");
        float body = style.bodySize(), gap = style.scale();
        add(blocks, name.isBlank() ? title : name, style.nameSize(), true, 0, 8 * gap, false);
        add(blocks, text(basics, "title"), body, false, 0, 3 * gap, false);
        List<String> contact = new ArrayList<>(List.of(text(basics, "phone"), text(basics, "email"), text(basics, "city")));
        // Compact keeps links on the contact line to save vertical space.
        if (style.compact()) for (JsonNode link : basics.path("links")) contact.add(clean(link.asText()));
        add(blocks, join(contact.toArray(String[]::new)), style.compact() ? 9 : 10, false, 0, 3 * gap, false);
        if (!style.compact()) for (JsonNode link : basics.path("links")) add(blocks, link.asText(), body, false, 0, 3, false);
        Map<String, List<Block>> sections = new LinkedHashMap<>();
        sections.put("个人摘要", List.of(new Block(clean(root.path("summary").asText()), body, false, 0, 3 * gap, false)));
        List<Block> entries = new ArrayList<>();
        for (JsonNode item : root.path("education")) {
            add(entries, join(text(item, "school"), text(item, "degree"), text(item, "major"), dates(item)), body, true, 0, 4 * gap, false);
            String gpa = text(item, "gpa");
            if (!gpa.isBlank()) add(entries, "GPA / 成绩：" + gpa, body, false, 0, 3 * gap, false);
        }
        sections.put("教育经历", entries);
        entries = new ArrayList<>();
        for (JsonNode item : root.path("experience")) {
            add(entries, join(text(item, "org"), text(item, "title"), dates(item)), body, true, 0, 4 * gap, false);
            bullets(entries, item.path("bullets"), style);
        }
        sections.put("工作经历", entries);
        entries = new ArrayList<>();
        for (JsonNode item : root.path("projects")) {
            add(entries, join(text(item, "name"), text(item, "role"), dates(item)), body, true, 0, 4 * gap, false);
            String tech = strings(item.path("tech"));
            if (!tech.isBlank()) add(entries, "技术栈：" + tech, body, false, 0, 3 * gap, false);
            bullets(entries, item.path("bullets"), style);
        }
        sections.put("项目经历", entries);
        entries = new ArrayList<>();
        for (JsonNode item : root.path("skills")) {
            String category = text(item, "category"), items = strings(item.path("items"));
            add(entries, category.isBlank() ? items : category + (items.isBlank() ? "" : "：" + items), body, false, 0, 3 * gap, false);
        }
        sections.put("专业技能", entries);
        entries = new ArrayList<>();
        if (style.compact()) add(entries, strings(root.path("certificates")), body, false, 0, 3 * gap, false);
        else for (JsonNode certificate : root.path("certificates")) add(entries, certificate.asText(), body, false, 0, 3, false);
        sections.put("证书", entries);
        List<String> order = style.compact()
                ? List.of("个人摘要", "专业技能", "教育经历", "项目经历", "工作经历", "证书")
                : List.of("个人摘要", "教育经历", "工作经历", "项目经历", "专业技能", "证书");
        for (String label : order) section(blocks, label, sections.get(label), style);
        return blocks;
    }
    private static void section(List<Block> blocks, String label, List<Block> entries, Style style) {
        List<Block> visible = entries.stream().filter(block -> !block.text().isBlank()).toList();
        if (visible.isEmpty()) return;
        add(blocks, label, style.headingSize(), true, 0, 6 * style.scale(), true);
        blocks.addAll(visible);
    }
    private static void bullets(List<Block> blocks, JsonNode array, Style style) {
        for (JsonNode item : array)
            if (!item.asText().isBlank()) add(blocks, "• " + item.asText(), style.bodySize(), false, style.compact() ? 8 : 10, 3 * style.scale(), false);
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

    private static final String NO_LINE_START = "。，、；：！？）］｝】》〉」』”’…,.!?;:)]}";
    private static final String NO_LINE_END = "（［｛【《〈「『“‘([{";

    /** Wrap at Chinese/word boundaries, keeping punctuation with its neighbouring text. */
    static List<String> wrap(String text, ResumePdfFont font, float size, float maxWidth) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            int[] points = paragraph.codePoints().toArray();
            double[] widths = new double[points.length + 1];
            for (int i = 0; i < points.length; i++) widths[i + 1] = widths[i] + font.width(points[i], size);
            if (points.length == 0) lines.add("");
            for (int start = 0; start < points.length;) {
                int fit = start;
                while (fit < points.length && widths[fit + 1] - widths[start] <= maxWidth) fit++;
                // Always consume a code point, even if the available width is narrower than a glyph.
                fit = Math.max(start + 1, fit);
                int end = fit;
                while (end > start && !canBreak(points, end)) end--;
                if (end == start) {
                    int wordStart = fit, wordEnd = fit;
                    while (wordStart > start && wordPoint(points[wordStart - 1])) wordStart--;
                    while (wordEnd < points.length && wordPoint(points[wordEnd])) wordEnd++;
                    if (widths[wordEnd] - widths[wordStart] > maxWidth) {
                        // Only a word wider than the whole line may be split internally.
                        end = fit;
                        while (end < points.length && !punctuationBreak(points, end)) end++;
                    } else {
                        // A short indivisible phrase/closing mark may hang beyond the margin.
                        end = fit;
                        while (end < points.length && !canBreak(points, end)) end++;
                    }
                }
                lines.add(new String(points, start, end - start));
                start = end;
            }
        }
        return lines;
    }

    private static boolean canBreak(int[] points, int at) {
        return at == points.length || (punctuationBreak(points, at)
            && !(wordPoint(points[at - 1]) && wordPoint(points[at])));
    }
    private static boolean punctuationBreak(int[] points, int at) {
        int before = at - 1, after = at;
        while (before >= 0 && Character.isWhitespace(points[before])) before--;
        while (after < points.length && Character.isWhitespace(points[after])) after++;
        return (before < 0 || NO_LINE_END.indexOf(points[before]) < 0)
            && (after == points.length || NO_LINE_START.indexOf(points[after]) < 0);
    }
    private static boolean wordPoint(int point) {
        // Keep printable ASCII runs intact, including URL paths, queries and fragments.
        return (point >= '!' && point <= '~') || Character.isDigit(point)
            || Character.UnicodeScript.of(point) == Character.UnicodeScript.LATIN;
    }

    private static final class Layout implements AutoCloseable {
        private final float MARGIN;
        private final Style style;
        private final PDDocument document;
        private final ResumePdfFont regular, bold;
        private PDPageContentStream content;
        private float y;
        Layout(PDDocument document, ResumePdfFont regular, ResumePdfFont bold, Style style) throws IOException {
            this.document = document; this.regular = regular; this.bold = bold; this.style = style; this.MARGIN = style.margin();
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
            float leading = block.size() * (style.compact() ? 1.4f : 1.5f);
            // Keep section titles with at least the first content line on the same page.
            float reserve = block.heading() ? leading + 36 : leading;
            if (y - reserve < MARGIN) page();
            if (block.heading() && style.compact()) {
                // Accent bar left of the title instead of a full-width rule.
                y -= 6;
                content.setNonStrokingColor(.16f, .38f, .74f);
                content.addRect(MARGIN, y - leading + 2, 3, block.size());
                content.fill();
                content.setNonStrokingColor(0f);
            } else if (block.heading()) {
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
                content.newLineAtOffset(MARGIN + block.indent() + (block.heading() && style.compact() ? 8 : 0), y);
                font.show(content, line, block.size());
                content.endText();
            }
            y -= block.gap();
        }
        @Override public void close() throws IOException { if (content != null) content.close(); }
    }
}
