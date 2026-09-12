package com.agent.sense.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 2 感官期配置（R2-01~R2-10）
 */
@Data
@ConfigurationProperties(prefix = "sense")
public class SenseProperties {

    /** 本地文件采集根目录（限制采集范围，防止任意文件读取） */
    private String fileRoot = System.getProperty("user.dir") + "/data/sense-inbox";

    /** 单次采集最大字节数 */
    private long maxBytes = 2 * 1024 * 1024L;

    /** 允许抓取的域名白名单（为空表示不限制，仍受 SSRF 私网拦截） */
    private String allowedHosts = "";

    private Timeouts timeouts = new Timeouts();
    private Retry retry = new Retry();
    private Quality quality = new Quality();
    private Ocr ocr = new Ocr();
    private Health health = new Health();
    private Staging staging = new Staging();
    private R1 r1 = new R1();
    private R0 r0 = new R0();

    @Data
    public static class Timeouts {
        private int connectMs = 5000;
        private int readMs = 10000;
    }

    /** R2-09 指数退避重试 */
    @Data
    public static class Retry {
        private int maxAttempts = 3;
        private long initialMs = 200;
        private double multiplier = 2.0;
        private long maxMs = 2000;
    }

    /** R2-04/R2-10 质检门槛 */
    @Data
    public static class Quality {
        private double threshold = 0.6;
        private int minLength = 20;
        private double minPrintableRatio = 0.8;
    }

    /** R2-03 视觉渠道 OCR（Python nlp-service PaddleOCR 接口） */
    @Data
    public static class Ocr {
        private boolean enabled = true;
        private String url = "http://127.0.0.1:8000/api/nlp/ocr";
        private int timeoutMs = 20000;
        /** 视觉渠道置信度基线（OCR 基线可接受较低准确率） */
        private double baselineConfidence = 0.55;
    }

    /** R2-09 渠道健康探针 */
    @Data
    public static class Health {
        private long probeMs = 30000;
        private int degradeAfter = 1;
        private int downAfter = 3;
    }

    /** R2-10 隔离暂存区 */
    @Data
    public static class Staging {
        /** auto / minio / local */
        private String backend = "auto";
        private String minioEndpoint = "http://127.0.0.1:9000";
        private String minioAccessKey = "agent";
        private String minioSecretKey = "agent123456";
        private String bucket = "lifeform-staging";
        private String localRoot = System.getProperty("user.dir") + "/data/sense-staging";
    }

    /** R2-06 R1 动态采集（单轮渠道数上限，防采集风暴） */
    @Data
    public static class R1 {
        private int maxChannelsPerRound = 3;
        private int maxItemsPerRound = 10;
    }

    /** R2-05 R0 默认采集调度 */
    @Data
    public static class R0 {
        private boolean enabled = true;
        private long tickMs = 60000;
        private List<Rule> rules = new ArrayList<>();

        @Data
        public static class Rule {
            private String id;
            /** TOUCH / VISUAL / AUDIO / NOSE / TASTE */
            private String channel = "TOUCH";
            private String dataSource = "";
            private String query = "";
            private String tenantId = "default";
            /** 采集周期（秒），0 表示每 tick 触发 */
            private long intervalSeconds = 300;
            private boolean enabled = true;
            /** 该规则采集内容的标准时效标记（REALTIME/NEAR_REALTIME/BATCH/STALE） */
            private String freshness = "NEAR_REALTIME";
        }
    }
}
