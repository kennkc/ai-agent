package com.agent.sense.channel;

import com.agent.sense.config.SenseProperties;
import com.agent.sense.security.OutboundGuard;
import com.agent.sense.util.TextExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 触觉渠道（R2-02）：URL 抓取 / 本地文件读取 / 纯文本处理
 * - URL：SSRF 守卫 + 重定向上限 + 大小上限 + HTML 正文提取
 * - 文件：限定 file-root 目录内，防路径穿越
 * - 文本：直接标准化
 */
@Slf4j
@Component
public class TouchChannel implements SenseChannel {
    private static final int MAX_REDIRECTS = 3;


    /** 采集类型，用于区分同一渠道内的数据源形态 */
    public enum SourceKind { URL, FILE, TEXT }

    private final SenseProperties properties;
    private final Set<String> allowedHosts;
    private final HttpClient httpClient;

    @Autowired
    public TouchChannel(SenseProperties properties) {
        this.properties = properties;
        this.allowedHosts = OutboundGuard.parseAllowedHosts(properties.getAllowedHosts());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeouts().getConnectMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** 测试/独立使用便捷构造 */
    public TouchChannel() { this(new SenseProperties()); }

    @Override public ChannelType type() { return ChannelType.TOUCH; }
    @Override public boolean register(Map<String, String> config) { return true; }
    @Override public boolean healthy() { return true; }
    @Override public void close() { /* 无外部连接需要释放 */ }

    @Override
    public CollectResult collect(CollectRequest request) {
        CollectResult result = new CollectResult();
        result.setBatchId(UUID.randomUUID().toString());
        result.setSourceChannel(ChannelType.TOUCH.name());
        long started = System.currentTimeMillis();
        try {
            SourceKind kind = kindOf(request.getDataSource());
            String raw = switch (kind) {
                case URL -> fetchUrlContent(request.getDataSource());
                case FILE -> readFile(request.getDataSource());
                case TEXT -> request.getDataSource();
            };
            TextExtractor.Extracted extracted = TextExtractor.extract(raw);
            String content = extracted.content();
            result.setContent(content);
            result.setItemCount(content.isBlank() ? 0 : 1);
            result.setQualityScore(content.isBlank() ? 0 : 1.0);
            result.setAccepted(!content.isBlank());
            // 触觉渠道置信度：URL/文件为结构化来源，略高于纯文本
            result.setConfidence(kind == SourceKind.TEXT ? 0.8 : 0.9);
            result.setFreshness(kind == SourceKind.FILE ? "BATCH" : "NEAR_REALTIME");
            if (content.isBlank()) result.setError("empty content from " + kind);
        } catch (Exception e) {
            log.warn("TouchChannel collection failed: {}", e.getMessage());
            result.setItemCount(0);
            result.setQualityScore(0);
            result.setAccepted(false);
            result.setError(e.getMessage());
        }
        log.debug("touch collect cost={}ms", System.currentTimeMillis() - started);
        return result;
    }

    SourceKind kindOf(String dataSource) {
        if (dataSource == null || dataSource.isBlank()) {
            throw new IllegalArgumentException("dataSource must not be blank");
        }
        String v = dataSource.strip();
        if (v.startsWith("http://") || v.startsWith("https://")) return SourceKind.URL;
        if (v.startsWith("file://") || v.startsWith("file:") || v.startsWith("local://")) return SourceKind.FILE;
        return SourceKind.TEXT;
    }

    /** 抓取公网 URL 并抽取正文（供同包渠道复用，含 SSRF 守卫与大小上限） */
    String fetchUrlContent(String dataSource) throws Exception {
        URI uri = URI.create(dataSource);
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            OutboundGuard.assertPublicHttpUrl(uri, allowedHosts);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(Duration.ofMillis(properties.getTimeouts().getReadMs()))
                    .header("User-Agent", "agent-lifeform-sense/0.2")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (isRedirect(response.statusCode())) {
                if (redirect == MAX_REDIRECTS) throw new IllegalStateException("too many redirects");
                String location = response.headers().firstValue("Location")
                        .orElseThrow(() -> new IllegalStateException("redirect without Location"));
                URI next = uri.resolve(location);
                if ("https".equalsIgnoreCase(uri.getScheme()) && "http".equalsIgnoreCase(next.getScheme())) {
                    throw new IllegalStateException("HTTPS downgrade redirect is forbidden");
                }
                uri = next;
                continue;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            if (response.body().length > properties.getMaxBytes()) {
                throw new IllegalArgumentException("response exceeds max size: " + response.body().length);
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            return TextExtractor.decode(response.body(), contentType);
        }
        throw new IllegalStateException("redirect limit exceeded");
    }

    private static boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303
                || statusCode == 307 || statusCode == 308;
    }
    /** 读取 file-root 白名单目录内的本地文件 */
    private String readFile(String dataSource) throws IOException {
        byte[] bytes = readBinaryForChannel(dataSource, properties.getMaxBytes());
        Path target = resolveInFileRoot(dataSource);
        return TextExtractor.decode(bytes, sniffContentType(target));
    }

    /**
     * 供同包渠道（视觉渠道取图）复用的受限二进制读取：
     * 只允许 file-root 白名单目录内的常规文件，且有大小上限。
     */
    byte[] readBinaryForChannel(String dataSource, long maxBytes) throws IOException {
        Path target = resolveInFileRoot(dataSource);
        long size = Files.size(target);
        long limit = maxBytes > 0 ? maxBytes : properties.getMaxBytes();
        if (size > limit) throw new IllegalArgumentException("file exceeds max size: " + size);
        return Files.readAllBytes(target);
    }

    private Path resolveInFileRoot(String dataSource) throws IOException {
        String raw = dataSource == null ? "" : dataSource.strip();
        // 注意：不能用 URI.getPath()，因为 file://page.html 会把 page.html 解析成 host、path 为空
        if (raw.startsWith("file://")) raw = raw.substring("file://".length());
        else if (raw.startsWith("file:")) raw = raw.substring("file:".length());
        else if (raw.startsWith("local://")) raw = raw.substring("local://".length());
        // 兼容 file:///C:/... 这类绝对路径写法：剥离前导斜杠后交由 file-root 白名单校验
        raw = raw.replace('\\', '/').replaceAll("^/+", "");
        Path root = Path.of(properties.getFileRoot()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path target = root.resolve(raw).normalize();
        if (!target.startsWith(root)) {
            throw new SecurityException("path escapes sense file-root: " + raw);
        }
        if (!Files.exists(target) || !Files.isRegularFile(target)) {
            throw new IOException("file not found in sense file-root: " + raw);
        }
        return target;
    }

    private String sniffContentType(Path target) {
        String name = target.getFileName().toString().toLowerCase();
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html; charset=UTF-8";
        if (name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".csv") || name.endsWith(".json")) {
            return "text/plain; charset=UTF-8";
        }
        return "";
    }

    /** 兼容 Phase1 单测：仅校验 URL 可用性 */
    public void assertPublicHttpUrl(URI uri) {
        OutboundGuard.assertPublicHttpUrl(uri, allowedHosts);
    }
}
