package com.offerpilot.common.document;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Local text-layer extraction only; never renders pages or sends content to a model. */
@Component
public class DocumentTextReader {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final int MAX_PAGES = 10;
    public record Text(String content, String mime) {}

    public Text read(String filename, String declaredMime, byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw invalid("文件为空，请选择包含正文的文件");
        if (bytes.length > MAX_BYTES) throw new BusinessException(ErrorCode.FILE_TOO_LARGE, "资料文件不能超过 10MB");
        String name = filename.toLowerCase(Locale.ROOT);
        String mime;
        String content;
        if (name.endsWith(".pdf")) {
            mime = "application/pdf";
            if (!starts(bytes, "%PDF-")) throw invalid("文件内容与 PDF 扩展名不一致");
            checkMime(declaredMime, mime, false);
            content = pdf(bytes);
        } else if (name.endsWith(".txt") || name.endsWith(".md")) {
            mime = name.endsWith(".md") ? "text/markdown" : "text/plain";
            checkMime(declaredMime, mime, true);
            if (starts(bytes, "%PDF-") || starts(bytes, "PK\003\004")) throw invalid("文件内容与文本扩展名不一致");
            content = utf8(bytes);
        } else throw new BusinessException(ErrorCode.FILE_TYPE_UNSUPPORTED, "仅支持 PDF、MD、TXT 文件（UTF-8 文本）");
        content = content.replace("\uFEFF", "").strip();
        if (content.isBlank()) throw invalid("没有提取到正文；PDF 须包含可复制的文本，暂不支持图片 OCR");
        if (content.indexOf('\0') >= 0) throw invalid("正文包含无效字符，请重新导出 UTF-8 文本或 PDF");
        return new Text(content, mime);
    }

    public static String utf8(byte[] bytes) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (text.indexOf('\0') >= 0) throw invalid("文本文件必须是 UTF-8 编码，且不能包含空字符");
            return text;
        } catch (CharacterCodingException ex) { throw invalid("文本文件必须是 UTF-8 编码"); }
    }

    private String pdf(byte[] bytes) {
        try (PDDocument pdf = Loader.loadPDF(bytes)) {
            if (pdf.isEncrypted()) throw invalid("请先解除 PDF 密码保护，再上传");
            if (pdf.getNumberOfPages() < 1 || pdf.getNumberOfPages() > MAX_PAGES) throw invalid("PDF 资料须为 1–10 页");
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            // Bound retained text; the caller truncates by Unicode code points and reports it.
            var text = new StringBuilder();
            stripper.writeText(pdf, new java.io.Writer() {
                @Override public void write(char[] value, int offset, int length) {
                    int remaining = 60010 - text.length();
                    if (remaining > 0) text.append(value, offset, Math.min(length, remaining));
                }
                @Override public void flush() {}
                @Override public void close() {}
            });
            return text.toString();
        } catch (InvalidPasswordException ex) { throw invalid("请先解除 PDF 密码保护，再上传"); }
        catch (BusinessException ex) { throw ex; }
        catch (Exception ex) { throw invalid("PDF 损坏或格式无效，请重新导出后上传"); }
    }

    private void checkMime(String declared, String expected, boolean text) {
        String mime = declared == null ? "" : declared.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        if (mime.isEmpty() || mime.equals("application/octet-stream") || mime.equals(expected)
                || (text && (mime.equals("text/plain") || mime.equals("text/markdown") || mime.equals("text/x-markdown")))) return;
        throw invalid("文件类型与扩展名不一致，请选择 PDF、MD 或 TXT 文件");
    }
    private static boolean starts(byte[] bytes, String prefix) {
        return bytes.length >= prefix.length() && new String(bytes, 0, prefix.length(), StandardCharsets.ISO_8859_1).equals(prefix);
    }
    private static BusinessException invalid(String message) { return new BusinessException(ErrorCode.VALIDATION_FAILED, message); }
}
