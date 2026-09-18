package com.agent.body.chunk;

import com.agent.body.common.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R3-02 入库链路「格式解析」步验收：
 * 多格式归一化无遗漏、HTML 去标签不吞正文、不支持格式**显式拒绝而非静默当文本**。
 */
class DocumentParserTest {

    @Test
    void markdownAndTextPassThroughUntouched() {
        String markdown = "# 标题\n正文内容。";
        assertEquals(markdown, DocumentParser.normalize(markdown, "md"));
        assertEquals(markdown, DocumentParser.normalize(markdown, "text"));
        assertEquals(markdown, DocumentParser.normalize(markdown, "TXT"), "格式名大小写不敏感");
    }

    @Test
    void autoSniffsHtmlButLeavesMarkdownAlone() {
        assertEquals("html", DocumentParser.detect("<html><body>正文</body></html>"));
        assertEquals("html", DocumentParser.detect("  <div>正文</div>"));
        assertEquals("text", DocumentParser.detect("# 标题\n正文"));
        assertEquals("text", DocumentParser.detect("a < b 是小于号"));
    }

    @Test
    void htmlIsStrippedToPlainTextWithoutLosingContent() {
        String html = """
                <html><head><title>标题</title><style>p{color:red}</style></head>
                <body>
                  <h1>躯体层架构</h1>
                  <p>知识以<b>分块</b>方式入库。</p>
                  <script>var x = 1;</script>
                  <ul><li>热层 Redis</li><li>温层 Qdrant</li></ul>
                </body></html>
                """;
        String text = DocumentParser.normalize(html, "html");

        assertTrue(text.contains("躯体层架构"), "标题不得丢失");
        assertTrue(text.contains("知识以分块方式入库"), "内联标签应被去掉但保留文字");
        assertTrue(text.contains("热层 Redis") && text.contains("温层 Qdrant"), "列表项应逐行保留");
        assertFalse(text.contains("<"), "标签应被清除：" + text);
        assertFalse(text.contains("var x"), "脚本内容不得进入正文");
        assertFalse(text.contains("color:red"), "样式内容不得进入正文");
    }

    @Test
    void htmlEntitiesAreDecoded() {
        assertEquals("A & B < C > D", DocumentParser.decodeEntities("A &amp; B &lt; C &gt; D"));
        assertEquals("© 2026 · 你好", DocumentParser.decodeEntities("&#169; 2026 &middot; 你好"));
        assertEquals("中", DocumentParser.decodeEntities("&#x4e2d;"));
        // 未知实体原样保留（不静默丢字符）
        assertEquals("&unknownentity;", DocumentParser.decodeEntities("&unknownentity;"));
    }

    @Test
    void unsupportedFormatsAreRejectedWithGuidanceNotSilentlyChunked() {
        byte[] pdf = "%PDF-1.7 binary".getBytes();
        BizException exception = assertThrows(BizException.class,
                () -> DocumentParser.normalize(new String(pdf), "pdf"));
        assertTrue(exception.getMessage().contains("PDF"), "应给出 PDF 处理指引：" + exception.getMessage());

        assertThrows(BizException.class, () -> DocumentParser.normalize("正文", "docx"));
        assertThrows(BizException.class, () -> DocumentParser.normalize("正文", "unknown-format"));
    }

    @Test
    void blankInputsAreRejected() {
        assertThrows(BizException.class, () -> DocumentParser.normalize(null, "auto"));
        assertThrows(BizException.class, () -> DocumentParser.normalize("   ", "auto"));
        // 解析后为空（只有标签）也必须报错，而不是产出空知识
        assertThrows(BizException.class, () -> DocumentParser.normalize("<div><span></span></div>", "html"));
    }

    @Test
    void ingestNormalizesHtmlBeforeChunking() {
        ChunkProcessor processor = new ChunkProcessor(800, 200, 50);
        String html = "<html><body><h1>存储分层</h1><p>热层 Redis、温层 Qdrant、冷层 PostgreSQL。</p></body></html>";
        String text = DocumentParser.normalize(html, "auto");
        var chunks = processor.chunk(text);
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.content().contains("热层 Redis")),
                "HTML 入库后应能分块并被检索");
        assertTrue(chunks.stream().noneMatch(chunk -> chunk.content().contains("<p>")),
                "分块内容中不应残留 HTML 标签");
    }
}
