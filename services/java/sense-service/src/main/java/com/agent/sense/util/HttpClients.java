package com.agent.sense.util;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 感官渠道统一使用的 HTTP 客户端构建器（缺陷 D-1 修复）。
 *
 * <p>{@link HttpClient} 默认协商 HTTP/2，对明文连接会先发送 {@code Upgrade: h2c} 握手；
 * uvicorn(h11) 不兼容该升级，会丢弃请求体导致 422。这里统一锁定 HTTP/1.1，
 * 渠道侧（取图、OCR 调用、外部 URL 抓取）一律经本类构建客户端，避免再次踩坑。
 */
public final class HttpClients {

    private HttpClients() {
    }

    /** 返回预置 HTTP/1.1 + 连接超时的构建器，调用方可继续追加自己的配置。 */
    public static HttpClient.Builder builder(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout);
    }
}
