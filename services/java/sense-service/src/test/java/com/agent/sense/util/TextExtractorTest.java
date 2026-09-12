package com.agent.sense.util;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R2-02 文本抽取与编码处理单测
 */
class TextExtractorTest {

    @Test
    void extractsTitleAndBodyFromHtml() {
        String html = "<html><head><title>架构说明</title>"
                + "<script>var a=1;</script><style>.x{color:red}</style></head>"
                + "<body><nav>导航</nav><h1>Agent-Lifeform</h1>"
                + "<p>网关负责鉴权与路由。</p><footer>版权</footer></body></html>";
        TextExtractor.Extracted extracted = TextExtractor.extract(html);
        assertEquals("架构说明", extracted.title());
        assertTrue(extracted.content().contains("Agent-Lifeform"));
        assertTrue(extracted.content().contains("网关负责鉴权与路由"));
        // 脚本 / 样式 / 导航 / 页脚噪声应被清洗
        assertFalse(extracted.content().contains("var a=1"));
        assertFalse(extracted.content().contains("color:red"));
        assertFalse(extracted.content().contains("导航"));
        assertFalse(extracted.content().contains("版权"));
    }

    @Test
    void keepsPlainTextUntouched() {
        String text = "这是一段纯文本采集内容，不会做 HTML 清洗。";
        TextExtractor.Extracted extracted = TextExtractor.extract(text);
        assertEquals("", extracted.title());
        assertEquals(text, extracted.content());
    }

    @Test
    void decodesUtf8ByContentTypeHeader() {
        byte[] body = "中文正文".getBytes(Charset.forName("UTF-8"));
        assertEquals("中文正文", TextExtractor.decode(body, "text/html; charset=UTF-8"));
    }

    @Test
    void decodesGbkWitoutHeader() {
        byte[] body = "国标编码正文".getBytes(Charset.forName("GBK"));
        assertEquals("国标编码正文", TextExtractor.decode(body, "text/html"));
    }

    @Test
    void decodesCharsetFromHtmlMeta() {
        byte[] body = "<html><head><meta charset=\"GBK\"></head><body>元数据声明编码</body></html>"
                .getBytes(Charset.forName("GBK"));
        String decoded = TextExtractor.decode(body, "");
        assertTrue(decoded.contains("元数据声明编码"));
    }

    @Test
    void handlesEmptyInput() {
        assertEquals("", TextExtractor.decode(new byte[0], "text/plain"));
        assertEquals("", TextExtractor.extract(null).content());
    }
}
