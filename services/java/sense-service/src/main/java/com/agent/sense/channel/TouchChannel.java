package com.agent.sense.channel;

// DEBT-006: 触觉单渠道（VS1/P2 简化版）— 触发点: P5 扩展五感官多渠道

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * 触觉渠道：URL/文件文本采集（R2-02）
 * Phase 0 提供最小实现，Phase 2 完善标准化与质检
 */
@Slf4j
@Component
public class TouchChannel implements SenseChannel {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public ChannelType type() {
        return ChannelType.TOUCH;
    }

    @Override
    public boolean register(Map<String, String> config) {
        log.info("TouchChannel 注册成功: {}", config);
        return true;
    }

    @Override
    public CollectResult collect(CollectRequest request) {
        CollectResult result = new CollectResult();
        result.setBatchId(UUID.randomUUID().toString());
        result.setSourceChannel("TOUCH");
        try {
            String content = fetchContent(request.getDataSource());
            result.setContent(content);
            result.setItemCount(content.isEmpty() ? 0 : 1);
            result.setQualityScore(1.0); // Phase 2 接入六维质检
            result.setAccepted(true);
        } catch (Exception e) {
            log.warn("TouchChannel 采集失败: {}", e.getMessage());
            result.setItemCount(0);
            result.setQualityScore(0);
            result.setAccepted(false);
        }
        return result;
    }

    @Override
    public boolean healthy() {
        return true;
    }

    @Override
    public void close() {
        log.info("TouchChannel 已关闭");
    }

    private String fetchContent(String dataSource) throws Exception {
        if (dataSource == null || dataSource.isBlank()) {
            throw new IllegalArgumentException("dataSource 不能为空");
        }
        // 支持 URL 或纯文本
        if (dataSource.startsWith("http://") || dataSource.startsWith("https://")) {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(dataSource))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("HTTP " + response.statusCode());
            }
            return stripHtml(response.body());
        }
        return dataSource; // 纯文本直接返回
    }

    private String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
