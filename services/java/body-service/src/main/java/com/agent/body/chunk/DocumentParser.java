package com.agent.body.chunk;

import com.agent.body.common.BizException;
import com.agent.body.common.ErrorCode;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档格式解析（R3-02 入库链路「格式解析」步）
 *
 * <p>设计 §5.1 入库流程为「上传 → <b>格式解析</b> → 分块 → 批量嵌入 → 向量入库 → 元数据」。
 * 分块器 {@link ChunkProcessor} 只接受纯文本，因此上传物的格式解析必须有人做——
 * 本类即该步的唯一入口，产出**纯文本**交给分块器。
 *
 * <p><b>支持范围（如实声明）</b>：
 * <ul>
 *   <li>{@code md} / {@code markdown} / {@code text} / {@code txt}：直通（Markdown 的结构由
 *       {@link ChunkProcessor} 按标题/段落边界解析，此处不做转换）；</li>
 *   <li>{@code html} / {@code htm}：去脚本样式 → 块级标签转换行 → 去标签 → 实体解码；</li>
 *   <li>{@code auto}（默认）：按内容嗅探（首字符 {@code <} 且含标签形 → html，否则 text）。</li>
 * </ul>
 *
 * <p><b>不支持：{@code pdf}</b> —— PDF 需要版面还原（PDFBox）或走 Phase 2 的 OCR 通道，
 * 本阶段不引入新解析依赖，故**显式拒绝并给出指引**，绝不把二进制当文本塞进分块器
 * （登记为 DEBT-013，触发点：接入文档解析通道时）。
 */
public final class DocumentParser {

    public static final String FORMAT_AUTO = "auto";

    /** 支持的格式 → 归一化策略 */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("auto", FORMAT_AUTO),
            Map.entry("md", "text"),
            Map.entry("markdown", "text"),
            Map.entry("text", "text"),
            Map.entry("txt", "text"),
            Map.entry("html", "html"),
            Map.entry("htm", "html"));

    private static final Map<String, String> UNSUPPORTED = Map.of(
            "pdf", "PDF 需版面还原；请先经 OCR/文档解析通道转文本后入库（DEBT-013）",
            "docx", "DOCX 需 Office 解析库；请先转 Markdown/文本后入库（DEBT-013）",
            "doc", "DOC 需 Office 解析库；请先转 Markdown/文本后入库（DEBT-013）");

    private static final Pattern SCRIPT_STYLE =
            Pattern.compile("(?is)<(script|style)\\b[^>]*>.*?</\\1\\s*>");
    private static final Pattern BLOCK_TAGS = Pattern.compile(
            "(?i)</?(p|div|br|li|ul|ol|tr|td|th|h[1-6]|section|article|header|footer|blockquote|pre|table|hr)\\b[^>]*>");
    private static final Pattern ANY_TAG = Pattern.compile("(?s)<[^>]+>");
    private static final Pattern TAG_HINT = Pattern.compile("(?s)^\\s*<(!doctype|html|body|div|p|section|article|h[1-6])\\b");
    private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");

    private static final Map<String, String> NAMED_ENTITIES = Map.ofEntries(
            Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"),
            Map.entry("quot", "\""), Map.entry("apos", "'"), Map.entry("nbsp", " "),
            Map.entry("mdash", "—"), Map.entry("ndash", "–"), Map.entry("hellip", "…"),
            Map.entry("middot", "·"), Map.entry("rsquo", "’"), Map.entry("lsquo", "‘"),
            Map.entry("ldquo", "“"), Map.entry("rdquo", "”"), Map.entry("times", "×"),
            Map.entry("copy", "©"), Map.entry("trade", "™"));

    private DocumentParser() {
    }

    /**
     * 归一化正文（入库唯一入口，见 {@code IngestService}）。
     *
     * @param content 原始正文
     * @param format  声明格式，null/空视为 {@code auto}
     * @return 可直接交给分块器的纯文本
     */
    public static String normalize(String content, String format) {
        if (content == null || content.isBlank()) {
            throw new BizException(ErrorCode.AGENT_BAD_REQUEST, "content 不能为空");
        }
        String declared = format == null || format.isBlank()
                ? FORMAT_AUTO
                : format.trim().toLowerCase(Locale.ROOT);
        String unsupported = UNSUPPORTED.get(declared);
        if (unsupported != null) {
            throw new BizException(ErrorCode.AGENT_BAD_REQUEST, "暂不支持 format=" + declared + "：" + unsupported);
        }
        String strategy = ALIASES.get(declared);
        if (strategy == null) {
            throw new BizException(ErrorCode.AGENT_BAD_REQUEST,
                    "未知 format=" + declared + "，支持：auto/md/text/html（pdf 见 DEBT-013）");
        }
        String detected = FORMAT_AUTO.equals(strategy) ? detect(content) : strategy;
        String normalized = "html".equals(detected) ? htmlToText(content) : content;
        String trimmed = normalized.strip();
        if (trimmed.isEmpty()) {
            throw new BizException(ErrorCode.AGENT_BAD_REQUEST, "格式解析后正文为空（format=" + declared + "）");
        }
        return trimmed;
    }

    /** 内容嗅探：仅用于 {@code auto}，宁可判成 text 也不误伤 Markdown */
    public static String detect(String content) {
        return TAG_HINT.matcher(content).find() ? "html" : "text";
    }

    /** HTML → 纯文本：去脚本样式 → 块级标签转换行 → 去标签 → 实体解码 → 折叠空行 */
    public static String htmlToText(String html) {
        String text = SCRIPT_STYLE.matcher(html).replaceAll("\n");
        text = BLOCK_TAGS.matcher(text).replaceAll("\n");
        text = ANY_TAG.matcher(text).replaceAll("");
        text = decodeEntities(text);
        text = text.replace('\u00a0', ' ').replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = text.split("\n", -1);
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            builder.append(line.strip()).append('\n');
        }
        return BLANK_LINES.matcher(builder.toString().strip()).replaceAll("\n\n").strip();
    }

    /** 实体解码：命名实体查表，数字实体按码点还原；未知实体原样保留（不静默丢字符） */
    public static String decodeEntities(String value) {
        Matcher matcher = ENTITY.matcher(value);
        StringBuilder builder = new StringBuilder();
        while (matcher.find()) {
            String body = matcher.group(1);
            String replacement = null;
            if (body.startsWith("#")) {
                try {
                    int codePoint = body.startsWith("#x") || body.startsWith("#X")
                            ? Integer.parseInt(body.substring(2), 16)
                            : Integer.parseInt(body.substring(1));
                    replacement = new String(Character.toChars(codePoint));
                } catch (RuntimeException ignored) {
                    replacement = null;
                }
            } else {
                replacement = NAMED_ENTITIES.get(body.toLowerCase(Locale.ROOT));
            }
            matcher.appendReplacement(builder,
                    Matcher.quoteReplacement(replacement == null ? matcher.group(0) : replacement));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }
}
