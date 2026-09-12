package com.agent.sense.channel;

import com.agent.sense.config.SenseProperties;
import com.agent.sense.security.OutboundGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 视觉渠道（R2-03）：图片 / 图像 → OCR → 文本化
 * OCR 能力由 Python nlp-service（PaddleOCR，未安装时降级为轻量占位）提供，
 * Java 侧只做取图、编码、调用与置信度回填，保持渠道可插拔。
 */
@Slf4j
@Component
public class VisualChannel implements SenseChannel {

    private final SenseProperties properties;
    private final ObjectMapper objectMapper;
    private final TouchChannel touchChannel;    // 复用文件/URL 取图能力（含 SSRF 与路径穿越防护）
    private final HttpClient httpClient;
    private final Set<String> allowedHosts;

    private volatile boolean ocrReachable = true;
    private volatile long lastProbeAt = 0;

    public VisualChannel(SenseProperties properties, ObjectMapper objectMapper, TouchChannel touchChannel) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.touchChannel = touchChannel;
        this.allowedHosts = OutboundGuard.parseAllowedHosts(properties.getAllowedHosts());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getTimeouts().getConnectMs()))
                .build();
    }

    @Override public ChannelType type() { return ChannelType.VISUAL; }
    @Override public boolean register(Map<String, String> config) { return true; }

    @Override
    public boolean healthy() {
        if (!properties.getOcr().isEnabled()) return false;
        long now = System.currentTimeMillis();
        if (now - lastProbeAt < properties.getHealth().getProbeMs()) return ocrReachable;
        lastProbeAt = now;
        try {
            String base = stripSuffix(properties.getOcr().getUrl(), "/ocr");
            URI uri = URI.create(base + "/ocr/health");
            OutboundGuard.assertInternalHttpUrl(uri);
            HttpRequest request = HttpRequest.newBuilder().uri(uri)
                    .timeout(Duration.ofMillis(properties.getTimeouts().getConnectMs())).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            // 仅当 nlp-service 上报 OCR 引擎可用时，视觉渠道才算健康
            ocrReachable = response.statusCode() == 200 && response.body().contains("\"available\":true");
        } catch (Exception e) {
            log.debug("OCR probe failed: {}", e.getMessage());
            ocrReachable = false;
        }
        return ocrReachable;
    }

    @Override public void close() {}

    @Override
    public CollectResult collect(CollectRequest request) {
        CollectResult result = new CollectResult();
        result.setBatchId(UUID.randomUUID().toString());
        result.setSourceChannel(ChannelType.VISUAL.name());
        try {
            byte[] image = loadImage(request.getDataSource());
            OcrResponse ocr = invokeOcr(image, request.getDataSource());
            String text = ocr.text() == null ? "" : ocr.text().strip();
            result.setContent(text);
            result.setItemCount(text.isBlank() ? 0 : 1);
            result.setQualityScore(text.isBlank() ? 0 : 1.0);
            result.setAccepted(!text.isBlank());
            double conf = ocr.confidence() > 0 ? ocr.confidence() : properties.getOcr().getBaselineConfidence();
            result.setConfidence(Math.max(0.0, Math.min(1.0, conf)));
            result.setFreshness("NEAR_REALTIME");
            if (text.isBlank()) result.setError("OCR returned empty text (engine=" + ocr.engine() + ")");
        } catch (Exception e) {
            log.warn("VisualChannel collection failed: {}", e.getMessage());
            result.setItemCount(0);
            result.setQualityScore(0);
            result.setAccepted(false);
            result.setError(e.getMessage());
        }
        return result;
    }

    private byte[] loadImage(String dataSource) throws Exception {
        if (dataSource == null || dataSource.isBlank()) throw new IllegalArgumentException("image dataSource must not be blank");
        // 本地文件：走 TouchChannel 的 file-root 白名单读取（getContentAsString 不适用，这里直接调用其文件能力）
        if (dataSource.startsWith("http://") || dataSource.startsWith("https://")) {
            URI uri = URI.create(dataSource);
            OutboundGuard.assertPublicHttpUrl(uri, allowedHosts);
            HttpRequest httpRequest = HttpRequest.newBuilder().uri(uri)
                    .timeout(Duration.ofMillis(properties.getTimeouts().getReadMs())).GET().build();
            HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("HTTP " + response.statusCode());
            }
            if (response.body().length > properties.getMaxBytes()) {
                throw new IllegalArgumentException("image exceeds max size");
            }
            return response.body();
        }
        return touchChannel.readBinaryForChannel(dataSource, properties.getMaxBytes());
    }

    private OcrResponse invokeOcr(byte[] image, String source) throws Exception {
        if (!properties.getOcr().isEnabled()) throw new IllegalStateException("OCR is disabled by configuration");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("image_base64", Base64.getEncoder().encodeToString(image));
        payload.put("source", source);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(properties.getOcr().getUrl()))
                .timeout(Duration.ofMillis(properties.getOcr().getTimeoutMs()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("OCR HTTP " + response.statusCode());
        return objectMapper.readValue(response.body(), OcrResponse.class);
    }

    private static String stripSuffix(String value, String suffix) {
        return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
    }

    record OcrResponse(String text, double confidence, String engine) {}
}
