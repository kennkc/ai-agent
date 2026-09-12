package com.agent.sense.channel;

import com.agent.sense.config.SenseProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R2-01/R2-02/R2-06 渠道抽象与触觉渠道采集单测
 */
class TouchChannelTest {

    private SenseProperties props(Path fileRoot) {
        SenseProperties properties = new SenseProperties();
        properties.setFileRoot(fileRoot.toString());
        return properties;
    }

    @Test
    void blocksLoopbackUrl() {
        TouchChannel channel = new TouchChannel();
        SenseChannel.CollectRequest request = new SenseChannel.CollectRequest();
        request.setDataSource("http://127.0.0.1:8080/secret");
        SenseChannel.CollectResult result = channel.collect(request);
        assertFalse(result.isAccepted());
        assertNotNull(result.getError());
    }

    @Test
    void blocksPrivateNetworkUrl() {
        TouchChannel channel = new TouchChannel();
        SenseChannel.CollectRequest request = new SenseChannel.CollectRequest();
        request.setDataSource("http://192.168.1.10/admin");
        assertFalse(channel.collect(request).isAccepted());
    }

    @Test
    void blocksIpv6UniqueLocalUrl() {
        TouchChannel channel = new TouchChannel();
        SenseChannel.CollectRequest request = new SenseChannel.CollectRequest();
        request.setDataSource("http://[fc00::1]/admin");
        assertFalse(channel.collect(request).isAccepted());
    }

    @Test
    void blocksCarrierGradeNatUrl() {
        TouchChannel channel = new TouchChannel();
        SenseChannel.CollectRequest request = new SenseChannel.CollectRequest();
        request.setDataSource("http://100.64.0.1/admin");
        assertFalse(channel.collect(request).isAccepted());
    }
    @Test
    void acceptsPlainText() {
        TouchChannel channel = new TouchChannel();
        SenseChannel.CollectRequest request = new SenseChannel.CollectRequest();
        request.setDataSource("hello knowledge");
        SenseChannel.CollectResult result = channel.collect(request);
        assertTrue(result.isAccepted());
        assertEquals("hello knowledge", result.getContent());
        assertEquals(0.8, result.getConfidence());
    }

    @Test
    void readsFileInsideFileRoot(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("news.txt"), "本地文件采集内容，UTF-8 编码。", Charset.forName("UTF-8"));
        TouchChannel channel = new TouchChannel(props(root));
        SenseChannel.CollectRequest request = new SenseChannel.CollectRequest();
        request.setDataSource("local://news.txt");
        SenseChannel.CollectResult result = channel.collect(request);
        assertTrue(result.isAccepted());
        assertTrue(result.getContent().contains("本地文件采集内容"));
        assertEquals("BATCH", result.getFreshness());
    }

    @Test
    void readsHtmlFileWithBodyExtraction(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("page.html"),
                "<html><head><title>页面标题</title><script>x()</script></head>"
                        + "<body><div>正文第一句。</div><div>正文第二句。</div></body></html>",
                Charset.forName("UTF-8"));
        TouchChannel channel = new TouchChannel(props(root));
        SenseChannel.CollectRequest request = new SenseChannel.CollectRequest();
        request.setDataSource("file://page.html");
        SenseChannel.CollectResult result = channel.collect(request);
        assertTrue(result.isAccepted());
        assertTrue(result.getContent().contains("正文第一句"));
        assertFalse(result.getContent().contains("x()"));
    }

    @Test
    void blocksPathTraversal(@TempDir Path root) throws Exception {
        TouchChannel channel = new TouchChannel(props(root));
        SenseChannel.CollectRequest request = new SenseChannel.CollectRequest();
        request.setDataSource("local://../../etc/passwd");
        SenseChannel.CollectResult result = channel.collect(request);
        assertFalse(result.isAccepted());
        assertNotNull(result.getError());
    }

    @Test
    void reportsMissingFileAsFailure(@TempDir Path root) {
        TouchChannel channel = new TouchChannel(props(root));
        SenseChannel.CollectRequest request = new SenseChannel.CollectRequest();
        request.setDataSource("local://not-exists.txt");
        assertFalse(channel.collect(request).isAccepted());
    }

    @Test
    void rejectsBlankDataSource() {
        TouchChannel channel = new TouchChannel();
        SenseChannel.CollectRequest request = new SenseChannel.CollectRequest();
        request.setDataSource("   ");
        assertFalse(channel.collect(request).isAccepted());
    }

    @Test
    void registrySupportsPluggableChannel(@TempDir Path root) {
        // R2-01 验收：新增渠道不修改核心代码即可接入
        SenseChannel custom = new SenseChannel() {
            @Override public ChannelType type() { return ChannelType.NOSE; }
            @Override public boolean register(Map<String, String> config) { return true; }
            @Override public CollectResult collect(CollectRequest request) {
                CollectResult result = new CollectResult();
                result.setBatchId("custom-batch");
                result.setSourceChannel("NOSE");
                result.setContent("custom channel content");
                result.setItemCount(1);
                result.setAccepted(true);
                return result;
            }
            @Override public boolean healthy() { return true; }
            @Override public void close() { }
        };
        ChannelRegistry registry = new ChannelRegistry(List.of(new TouchChannel(props(root)), custom));
        assertEquals(2, registry.size());
        assertTrue(registry.find(SenseChannel.ChannelType.NOSE).isPresent());
        assertEquals("custom-batch", registry.require(SenseChannel.ChannelType.NOSE)
                .collect(new SenseChannel.CollectRequest()).getBatchId());
    }

    @Test
    void channelTypeParsesChineseLabel() {
        assertEquals(SenseChannel.ChannelType.VISUAL, SenseChannel.ChannelType.parse("视觉"));
        assertEquals(SenseChannel.ChannelType.TOUCH, SenseChannel.ChannelType.parse("touch"));
        assertEquals(SenseChannel.ChannelType.TOUCH, SenseChannel.ChannelType.parse(null));
        assertThrows(IllegalArgumentException.class, () -> SenseChannel.ChannelType.parse("brain"));
    }
}
