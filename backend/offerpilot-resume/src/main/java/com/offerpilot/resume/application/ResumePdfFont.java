package com.offerpilot.resume.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.apache.fontbox.ttf.*;
import org.apache.pdfbox.cos.*;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType3Font;

import java.awt.geom.PathIterator;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Embeds only used vector glyphs with ToUnicode, including Noto CJK's CFF outlines.
 * PDFBox's PDType0Font embedding supports TrueType outlines, not the system CFF TTCs.
 * Type 3 glyph programs keep PDFs portable and searchable without bundling whole fonts.
 */
final class ResumePdfFont implements AutoCloseable {
    private final Closeable source;
    private final TrueTypeFont face;
    private final Map<Integer, Glyph> glyphs = new LinkedHashMap<>();
    private record Glyph(PDType3Font font, int code, float width) {}

    private ResumePdfFont(Closeable source, TrueTypeFont face) {
        this.source = source;
        this.face = face;
    }

    static ResumePdfFont open(String configured, boolean bold) {
        String weight = bold ? "Bold" : "Regular";
        List<String> paths = configured.isBlank() ? List.of(
            "/usr/share/fonts/opentype/noto/NotoSansCJK-" + weight + ".ttc",
            "/usr/share/fonts/noto/NotoSansCJK-" + weight + ".ttc",
            "/usr/share/fonts/noto-cjk/NotoSansCJK-" + weight + ".ttc",
            "/usr/share/fonts/noto-cjk/NotoSansCJKsc-" + weight + ".otf"
        ) : List.of(configured);
        for (String path : paths) {
            if (!Files.isReadable(Path.of(path))) continue;
            Closeable source = null;
            try {
                TrueTypeFont face;
                byte[] magic;
                try (InputStream input = Files.newInputStream(Path.of(path))) { magic = input.readNBytes(4); }
                if (Arrays.equals(magic, "ttcf".getBytes(StandardCharsets.US_ASCII))) {
                    var collection = new TrueTypeCollection(new File(path));
                    source = collection;
                    face = collection.getFontByName("NotoSansCJKsc-" + weight);
                    if (face == null) {
                        List<TrueTypeFont> faces = new ArrayList<>();
                        collection.processAllFonts(faces::add);
                        face = faces.get(0);
                    }
                } else {
                    var input = new RandomAccessReadBufferedFile(path);
                    source = input;
                    face = new OTFParser().parse(input);
                }
                if (face.getUnicodeCmapLookup().getGlyphId('中') == 0) throw new IOException("No CJK glyphs");
                // Force outline parsing here so unusable default candidates can be skipped.
                outline(face, face.getUnicodeCmapLookup().getGlyphId('中'));
                return new ResumePdfFont(source, face);
            } catch (IOException | RuntimeException ex) {
                if (source != null) try { source.close(); } catch (IOException ignored) { /* candidate unusable */ }
            }
        }
        throw new BusinessException(ErrorCode.RESUME_EXPORT_UNAVAILABLE,
            "PDF 导出缺少可用中文字体，请安装 font-noto-cjk 或配置 offerpilot.resume.export.font-"
                + (bold ? "bold" : "regular"));
    }

    void embed(PDDocument document, Collection<String> texts) throws IOException {
        List<Integer> points = texts.stream().flatMapToInt(String::codePoints).filter(point -> point != '\n').distinct().boxed().toList();
        for (int offset = 0; offset < points.size(); offset += 255) {
            List<Integer> group = points.subList(offset, Math.min(offset + 255, points.size()));
            COSDictionary dictionary = new COSDictionary();
            dictionary.setItem(COSName.TYPE, COSName.FONT);
            dictionary.setItem(COSName.SUBTYPE, COSName.TYPE3);
            dictionary.setName(COSName.NAME, "Resume" + offset);
            dictionary.setItem(COSName.FONT_MATRIX, numbers(.001f, 0, 0, .001f, 0, 0));
            dictionary.setItem(COSName.FONT_BBOX, numbers(-1000, -1000, 2000, 2000));
            dictionary.setInt(COSName.FIRST_CHAR, 1);
            dictionary.setInt(COSName.LAST_CHAR, group.size());
            dictionary.setItem(COSName.RESOURCES, new COSDictionary());
            COSArray widths = new COSArray(), differences = new COSArray();
            COSDictionary programs = new COSDictionary(), encoding = new COSDictionary();
            differences.add(COSInteger.ONE);
            float scale = 1000f / face.getUnitsPerEm();
            List<Float> advances = new ArrayList<>();
            StringBuilder cmap = new StringBuilder("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n"
                + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
                + "/CMapName /ResumeUnicode def\n/CMapType 2 def\n1 begincodespacerange\n<00> <FF>\nendcodespacerange\n");
            for (int i = 0; i < group.size(); i++) {
                int point = group.get(i), code = i + 1;
                int gid = face.getUnicodeCmapLookup().getGlyphId(point);
                if (gid == 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "导出字体无法显示字符 U+" + Integer.toHexString(point).toUpperCase(Locale.ROOT) + "，请配置覆盖该字符的中文字体");
                float width = face.getAdvanceWidth(gid) * scale;
                advances.add(width);
                widths.add(new COSFloat(width));
                String name = "g" + code;
                differences.add(COSName.getPDFName(name));
                StringBuilder program = new StringBuilder(number(width) + " 0 d0\n");
                PathIterator iterator = outline(face, gid).getPathIterator(null);
                double[] coordinates = new double[6];
                double x = 0, y = 0;
                while (!iterator.isDone()) {
                    int kind = iterator.currentSegment(coordinates);
                    switch (kind) {
                        case PathIterator.SEG_MOVETO, PathIterator.SEG_LINETO -> {
                            program.append(number(coordinates[0] * scale)).append(' ')
                                .append(number(coordinates[1] * scale)).append(kind == PathIterator.SEG_MOVETO ? " m\n" : " l\n");
                            x = coordinates[0]; y = coordinates[1];
                        }
                        case PathIterator.SEG_QUADTO -> {
                            appendCurve(program, scale, x + (coordinates[0] - x) * 2 / 3,
                                y + (coordinates[1] - y) * 2 / 3,
                                coordinates[2] + (coordinates[0] - coordinates[2]) * 2 / 3,
                                coordinates[3] + (coordinates[1] - coordinates[3]) * 2 / 3,
                                coordinates[2], coordinates[3]);
                            x = coordinates[2]; y = coordinates[3];
                        }
                        case PathIterator.SEG_CUBICTO -> {
                            appendCurve(program, scale, coordinates);
                            x = coordinates[4]; y = coordinates[5];
                        }
                        case PathIterator.SEG_CLOSE -> program.append("h\n");
                        default -> throw new IOException("Unsupported glyph outline");
                    }
                    iterator.next();
                }
                program.append(iterator.getWindingRule() == PathIterator.WIND_EVEN_ODD ? "f*\n" : "f\n");
                programs.setItem(COSName.getPDFName(name), stream(document, program.toString()));
            }
            for (int start = 0; start < group.size(); start += 100) {
                int end = Math.min(start + 100, group.size());
                cmap.append(end - start).append(" beginbfchar\n");
                for (int i = start; i < end; i++) {
                    String unicode = HexFormat.of().formatHex(new String(Character.toChars(group.get(i))).getBytes(StandardCharsets.UTF_16BE));
                    cmap.append(String.format(Locale.ROOT, "<%02X> <%s>\n", i + 1, unicode));
                }
                cmap.append("endbfchar\n");
            }
            cmap.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n");
            encoding.setItem(COSName.TYPE, COSName.ENCODING);
            encoding.setItem(COSName.DIFFERENCES, differences);
            dictionary.setItem(COSName.ENCODING, encoding);
            dictionary.setItem(COSName.WIDTHS, widths);
            dictionary.setItem(COSName.CHAR_PROCS, programs);
            dictionary.setItem(COSName.TO_UNICODE, stream(document, cmap.toString()));
            PDType3Font font = new PDType3Font(dictionary);
            for (int i = 0; i < group.size(); i++) glyphs.put(group.get(i), new Glyph(font, i + 1, advances.get(i)));
        }
    }

    float width(int point, float size) { return glyphs.get(point).width() * size / 1000; }

    @SuppressWarnings("deprecation") // Type 3 has no PDFBox Unicode encoder; emit our mapped character codes.
    void show(PDPageContentStream content, String text, float size) throws IOException {
        PDType3Font active = null;
        StringBuilder codes = new StringBuilder();
        for (int point : text.codePoints().toArray()) {
            Glyph glyph = glyphs.get(point);
            if (active != glyph.font()) {
                if (!codes.isEmpty()) content.appendRawCommands("<" + codes + "> Tj\n");
                codes.setLength(0);
                active = glyph.font();
                content.setFont(active, size);
            }
            codes.append(String.format(Locale.ROOT, "%02X", glyph.code()));
        }
        if (!codes.isEmpty()) content.appendRawCommands("<" + codes + "> Tj\n");
    }

    private static java.awt.geom.GeneralPath outline(TrueTypeFont face, int gid) throws IOException {
        if (face instanceof OpenTypeFont otf && otf.isPostScript())
            return otf.getCFF().getFont().getType2CharString(gid).getPath();
        return face.getGlyph().getGlyph(gid).getPath();
    }
    private static void appendCurve(StringBuilder program, float scale, double... coordinates) {
        for (double value : coordinates) program.append(number(value * scale)).append(' ');
        program.append("c\n");
    }
    private static String number(double value) { return String.format(Locale.ROOT, "%.3f", value); }
    private static COSArray numbers(float... values) {
        COSArray array = new COSArray();
        for (float value : values) array.add(new COSFloat(value));
        return array;
    }
    private static PDStream stream(PDDocument document, String commands) throws IOException {
        return new PDStream(document, new ByteArrayInputStream(commands.getBytes(StandardCharsets.US_ASCII)), COSName.FLATE_DECODE);
    }
    @Override public void close() throws IOException { source.close(); }
}
