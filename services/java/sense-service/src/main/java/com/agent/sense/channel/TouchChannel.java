package com.agent.sense.channel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
public class TouchChannel implements SenseChannel {
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Set<String> allowedHosts = parseAllowedHosts(System.getenv("SENSE_ALLOWED_HOSTS"));

    @Override public ChannelType type() { return ChannelType.TOUCH; }
    @Override public boolean register(Map<String, String> config) { return true; }
    @Override public boolean healthy() { return true; }
    @Override public void close() {}

    @Override
    public CollectResult collect(CollectRequest request) {
        CollectResult result = new CollectResult();
        result.setBatchId(UUID.randomUUID().toString());
        result.setSourceChannel("TOUCH");
        try {
            String content = fetchContent(request.getDataSource());
            result.setContent(content);
            result.setItemCount(content.isEmpty() ? 0 : 1);
            result.setQualityScore(content.isEmpty() ? 0 : 1.0);
            result.setAccepted(!content.isEmpty());
        } catch (Exception e) {
            log.warn("TouchChannel collection rejected: {}", e.getMessage());
            result.setItemCount(0); result.setQualityScore(0); result.setAccepted(false);
        }
        return result;
    }

    private String fetchContent(String dataSource) throws Exception {
        if (dataSource == null || dataSource.isBlank()) throw new IllegalArgumentException("dataSource must not be blank");
        if (!dataSource.startsWith("http://") && !dataSource.startsWith("https://")) return dataSource;
        URI uri = URI.create(dataSource);
        assertPublicHttpUrl(uri);
        HttpRequest request = HttpRequest.newBuilder().uri(uri).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("HTTP " + response.statusCode());
        if (response.body().length > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("response exceeds max size");
        return stripHtml(new String(response.body(), StandardCharsets.UTF_8));
    }

    void assertPublicHttpUrl(URI uri) throws Exception {
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) throw new IllegalArgumentException("only http/https URLs are allowed");
        if (host == null || host.isBlank()) throw new IllegalArgumentException("URL host is required");
        if (!allowedHosts.isEmpty() && !allowedHosts.contains(host.toLowerCase())) throw new IllegalArgumentException("host is not in SENSE_ALLOWED_HOSTS");
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                throw new IllegalArgumentException("private or local network targets are forbidden");
            }
        }
    }

    private static Set<String> parseAllowedHosts(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isBlank()).map(String::toLowerCase).collect(Collectors.toUnmodifiableSet());
    }

    private String stripHtml(String html) { return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim(); }
}
