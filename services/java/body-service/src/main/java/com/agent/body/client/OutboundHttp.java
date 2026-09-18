package com.agent.body.client;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 跨服务 HTTP 调用的统一出口（D-1 缺陷修复的 body-service 副本）。
 *
 * <p>必须显式锁定 HTTP/1.1：JDK {@link HttpClient} 默认协商 HTTP/2，对明文连接会先发
 * {@code Upgrade: h2c} 握手，uvicorn(h11) 不兼容并<strong>丢弃请求体</strong>，
 * 表现为 FastAPI 422 → Java 侧误判上游不可用。Phase 3 新增对 nlp-service 的嵌入/重排调用，
 * 一律经由本类，不要直接使用 {@code RestClient.builder()}。
 */
public final class OutboundHttp {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /** 默认读超时：覆盖检索链路（向量化 + 重排）的 P99。 */
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);

    private OutboundHttp() {
    }

    public static HttpClient client() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    public static RestClient restClient(String baseUrl) {
        return restClient(baseUrl, DEFAULT_READ_TIMEOUT);
    }

    /**
     * 指定读超时的客户端。
     *
     * @param readTimeout 读超时；嵌入模型首次加载可能较慢（本地权重加载），调用方按需放宽
     */
    public static RestClient restClient(String baseUrl, Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client());
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
}
