package com.ke.nhservice.aimianshi.wrapper.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * PDF 取文本。PDFBox 3 的入口是 Loader.loadPDF，不再是 PDDocument.load。
 */
public final class PdfTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(PdfTextExtractor.class);

    /** 简历正文超过这个长度就截断——长简历塞进 prompt 又贵又没用 */
    private static final int MAX_CHARS = 8000;

    private PdfTextExtractor() {
    }

    /**
     * @throws IOException 文件损坏、加密、不是 PDF
     */
    public static String extract(byte[] bytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            String cleaned = cleanup(text);
            log.info("PDF 解析完成：{} 页，提取 {} 字符", document.getNumberOfPages(), cleaned.length());
            if (cleaned.isBlank()) {
                log.warn("PDF 解析结果为空，可能是扫描件（图片型 PDF），当前不支持 OCR");
            }
            return cleaned;
        }
    }

    private static String cleanup(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace("\r\n", "\n")
                .replace(' ', ' ')       // 不间断空格，PDF 里很常见
                .replaceAll("[ \t]+", " ")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
        return s.length() <= MAX_CHARS ? s : s.substring(0, MAX_CHARS);
    }
}