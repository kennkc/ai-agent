package com.agent.sense.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本抽取与编码处理（R2-02）
 * - HTML 正文提取（去脚本/样式/导航噪声，取 title + body 文本）
 * - 编码嗅探：Content-Type charset → BOM → UTF-8 严格解码 → GBK 回退
 */
public final class TextExtractor {

    private static final Pattern CHARSET_RE = Pattern.compile("charset\\s*=\\s*[\"']?([\\w-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_CHARSET_RE = Pattern.compile(
            "<meta[^>]+charset\\s*=\\s*[\"']?([\\w-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_RE = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SCRIPT_STYLE_RE = Pattern.compile("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>");

    private TextExtractor() {}

    /** 按 Content-Type / 内容自动解码字节流 */
    public static String decode(byte[] body, String contentType) {
        if (body == null || body.length == 0) return "";
        // 1) HTTP 头 charset
        String fromHeader = match(CHARSET_RE, contentType == null ? "" : contentType);
        String decoded = tryDecode(body, fromHeader);
        if (decoded != null) return decoded;
        // 2) BOM
        String bom = bomCharset(body);
        decoded = tryDecode(body, bom);
        if (decoded != null) return decoded;
        // 3) HTML meta charset
        String head = new String(body, 0, Math.min(body.length, 4096), StandardCharsets.ISO_8859_1);
        String fromMeta = match(META_CHARSET_RE, head);
        decoded = tryDecode(body, fromMeta);
        if (decoded != null) return decoded;
        // 4) UTF-8 严格解码 → 5) GBK 回退
        decoded = strictDecode(body, StandardCharsets.UTF_8);
        if (decoded != null) return decoded;
        decoded = strictDecode(body, Charset.forName("GBK"));
        if (decoded != null) return decoded;
        return new String(body, StandardCharsets.UTF_8);
    }

    /** 抽取正文：HTML 走 jsoup 清洗，纯文本原样返回 */
    public static Extracted extract(String raw) {
        if (raw == null) return new Extracted("", "");
        String trimmed = raw.stripLeading().toLowerCase(Locale.ROOT);
        boolean looksHtml = trimmed.startsWith("<!doctype") || trimmed.startsWith("<html")
                || trimmed.contains("<body") || trimmed.contains("<p>") || trimmed.contains("<div");
        if (!looksHtml) return new Extracted("", clean(raw));
        String title = "";
        Matcher tm = TITLE_RE.matcher(raw);
        if (tm.find()) title = clean(tm.group(1));
        String stripped = SCRIPT_STYLE_RE.matcher(raw).replaceAll(" ");
        Document doc = Jsoup.parse(stripped);
        doc.select("nav,footer,header,aside,form,button,iframe,.nav,.footer,.sidebar,.ad,.ads,.comment").remove();
        String body = doc.body() == null ? "" : doc.body().text();
        if (title.isBlank() && !doc.title().isBlank()) title = clean(doc.title());
        return new Extracted(title, clean(body));
    }

    private static String clean(String text) {
        return text.replace('\u00a0', ' ')
                .replaceAll("[\\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\s{2,}", " ")
                .replaceAll("(?m)^\\s+|\\s+$", "")
                .trim();
    }

    private static String match(Pattern p, String text) {
        if (text == null || text.isBlank()) return null;
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    private static String tryDecode(byte[] body, String charsetName) {
        if (charsetName == null || charsetName.isBlank()) return null;
        try {
            Charset cs = Charset.forName(charsetName.trim());
            String out = strictDecode(body, cs);
            return out == null || out.isBlank() ? null : out;
        } catch (Exception e) {
            return null;
        }
    }

    private static String strictDecode(byte[] body, Charset charset) {
        try {
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static String bomCharset(byte[] body) {
        if (body.length >= 3 && (body[0] & 0xFF) == 0xEF && (body[1] & 0xFF) == 0xBB && (body[2] & 0xFF) == 0xBF) return "UTF-8";
        if (body.length >= 2 && (body[0] & 0xFF) == 0xFE && (body[1] & 0xFF) == 0xFF) return "UTF-16BE";
        if (body.length >= 2 && (body[0] & 0xFF) == 0xFF && (body[1] & 0xFF) == 0xFE) return "UTF-16LE";
        return null;
    }

    public record Extracted(String title, String content) {}
}
