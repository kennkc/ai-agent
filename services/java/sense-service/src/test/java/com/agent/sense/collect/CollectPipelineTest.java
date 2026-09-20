package com.agent.sense.collect;

import com.agent.sense.channel.ChannelRegistry;
import com.agent.sense.channel.SenseChannel;
import com.agent.sense.config.SenseProperties;
import com.agent.sense.deadletter.DeadLetterStore;
import com.agent.sense.event.SenseEventPublisher;
import com.agent.sense.health.ChannelHealthMonitor;
import com.agent.sense.metrics.SenseMetrics;
import com.agent.sense.model.CollectedData;
import com.agent.sense.normalize.DataNormalizer;
import com.agent.sense.quality.QualityGate;
import com.agent.sense.staging.LocalStagingBackend;
import com.agent.sense.staging.MinioStagingBackend;
import com.agent.sense.staging.StagingStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 采集主流程单测：重试 / 降级 / 死信 / 质检拦截 / 暂存 / 指标
 */
class CollectPipelineTest {

    /** 可编排的假渠道：按脚本控制前 N 次失败 */
    static class ScriptedChannel implements SenseChannel {
        private final ChannelType type;
        private final AtomicInteger calls = new AtomicInteger();
        private final int failuresBeforeSuccess;
        private final String content;
        private boolean available = true;

        ScriptedChannel(ChannelType type, int failuresBeforeSuccess, String content) {
            this.type = type;
            this.failuresBeforeSuccess = failuresBeforeSuccess;
            this.content = content;
        }

        void setAvailable(boolean value) { this.available = value; }
        int calls() { return calls.get(); }

        @Override public ChannelType type() { return type; }
        @Override public boolean register(Map<String, String> config) { return true; }
        @Override public boolean healthy() { return available; }
        @Override public boolean available() { return available; }
        @Override public void close() { }

        @Override
        public CollectResult collect(CollectRequest request) {
            int attempt = calls.incrementAndGet();
            CollectResult result = new CollectResult();
            result.setBatchId("batch-" + type.name() + "-" + attempt);
            result.setSourceChannel(type.name());
            if (attempt <= failuresBeforeSuccess) {
                result.setAccepted(false);
                result.setItemCount(0);
                result.setQualityScore(0);
                result.setError("scripted failure #" + attempt);
                return result;
            }
            result.setAccepted(true);
            result.setContent(content);
            result.setItemCount(1);
            result.setQualityScore(1.0);
            result.setConfidence(0.9);
            return result;
        }
    }

    private SenseProperties properties;
    private DeadLetterStore deadLetterStore;
    private SenseMetrics metrics;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        properties = new SenseProperties();
        properties.setFileRoot(tempDir.resolve("inbox").toString());
        properties.getStaging().setBackend("local");
        properties.getStaging().setLocalRoot(tempDir.resolve("staging").toString());
        properties.getStaging().setMinioEndpoint("http://127.0.0.1:1");
        properties.getRetry().setMaxAttempts(3);
        properties.getRetry().setInitialMs(1);
        properties.getRetry().setMultiplier(1.0);
        deadLetterStore = new DeadLetterStore();
        metrics = new SenseMetrics();
    }

    private CollectPipeline pipeline(List<SenseChannel> channels) {
        ChannelRegistry registry = new ChannelRegistry(channels);
        ChannelHealthMonitor monitor = new ChannelHealthMonitor(registry, properties);
        LocalStagingBackend local = new LocalStagingBackend(properties);
        StagingStore stagingStore = new StagingStore(new MinioStagingBackend(properties), local);
        return new CollectPipeline(registry, monitor, new DataNormalizer(properties),
                new QualityGate(properties), stagingStore, metrics, new SenseEventPublisher(new ObjectMapper()),
                deadLetterStore, properties);
    }

    private SenseChannel.CollectRequest request() {
        SenseChannel.CollectRequest request = new SenseChannel.CollectRequest();
        request.setDataSource("采集测试数据源");
        request.setTenantId("default");
        request.setMode("R1_DYNAMIC");
        return request;
    }

    @Test
    void retriesUntilSuccessAndRecordsAttempts() {
        ScriptedChannel touch = new ScriptedChannel(SenseChannel.ChannelType.TOUCH, 2,
                "重试后成功的采集正文内容，长度足够通过质检门槛校验。");
        CollectPipeline pipeline = pipeline(List.of(touch));
        CollectedData data = pipeline.collect(SenseChannel.ChannelType.TOUCH, request());
        assertEquals(3, touch.calls());
        assertEquals(3, data.getAttempts());
        assertEquals(CollectedData.StagingStatus.ACCEPTED, data.getStagingStatus());
        assertTrue(data.fiveFieldsComplete());
    }

    @Test
    void exhaustedRetryGoesToDeadLetterAndIsRejected() {
        ScriptedChannel touch = new ScriptedChannel(SenseChannel.ChannelType.TOUCH, 99, "");
        CollectPipeline pipeline = pipeline(List.of(touch));
        CollectedData data = pipeline.collect(SenseChannel.ChannelType.TOUCH, request());
        assertEquals(3, touch.calls());
        assertEquals(CollectedData.StagingStatus.REJECTED, data.getStagingStatus());
        assertNotNull(data.getDeadLetterId());
        assertEquals(1, deadLetterStore.size());
        assertEquals(1, metrics.counter(SenseChannel.ChannelType.TOUCH).rejected());
    }

    @Test
    void lowQualityContentIsInterceptedBeforeStaging() {
        ScriptedChannel touch = new ScriptedChannel(SenseChannel.ChannelType.TOUCH, 0, "a");
        CollectPipeline pipeline = pipeline(List.of(touch));
        CollectedData data = pipeline.collect(SenseChannel.ChannelType.TOUCH, request());
        assertEquals(CollectedData.StagingStatus.REJECTED, data.getStagingStatus());
        assertTrue(data.getRejectReason().contains("QUALITY") || "BELOW_QUALITY_THRESHOLD".equals(data.getRejectReason()));
        assertNotNull(data.getDeadLetterId());
    }

    @Test
    void isolatedChannelDegradesToAvailableChannel() {
        ScriptedChannel visual = new ScriptedChannel(SenseChannel.ChannelType.VISUAL, 0,
                "视觉渠道采集内容示例，用于验证降级路由与五字段打标流程。");
        visual.setAvailable(false);
        ScriptedChannel touch = new ScriptedChannel(SenseChannel.ChannelType.TOUCH, 0,
                "触觉渠道降级后执行的采集正文内容，长度满足质检门槛要求。");
        CollectPipeline pipeline = pipeline(List.of(visual, touch));
        CollectedData data = pipeline.collect(SenseChannel.ChannelType.VISUAL, request());
        assertTrue(data.isDegraded());
        assertEquals("TOUCH", data.getSourceChannel());
        assertEquals(CollectedData.StagingStatus.ACCEPTED, data.getStagingStatus());
        assertEquals(0, visual.calls());
    }

    @Test
    void allFiveChannelsAreRegisteredAndDegradeAcrossRegistry() {
        ScriptedChannel visual = new ScriptedChannel(SenseChannel.ChannelType.VISUAL, 0, "视觉内容");
        ScriptedChannel audio = new ScriptedChannel(SenseChannel.ChannelType.AUDIO, 0, "听觉内容");
        ScriptedChannel nose = new ScriptedChannel(SenseChannel.ChannelType.NOSE, 0, "嗅觉内容");
        ScriptedChannel taste = new ScriptedChannel(SenseChannel.ChannelType.TASTE, 0, "味觉内容");
        ScriptedChannel touch = new ScriptedChannel(SenseChannel.ChannelType.TOUCH, 0,
                "五渠道协同降级后由触觉渠道返回的正文内容，长度满足质检门槛要求。");
        visual.setAvailable(false);
        audio.setAvailable(false);
        nose.setAvailable(false);
        taste.setAvailable(false);

        List<SenseChannel> channels = List.of(visual, audio, touch, nose, taste);
        ChannelRegistry registry = new ChannelRegistry(channels);
        assertEquals(5, registry.size(), "五个感官渠道必须全部注册");
        assertEquals(5, registry.healthSnapshot().size(), "健康快照必须覆盖五个渠道");
        assertEquals(1, registry.available().size(), "仅触觉渠道保持可用");
        assertEquals(SenseChannel.ChannelType.TOUCH, registry.available().get(0).type());

        CollectPipeline pipeline = pipeline(channels);
        CollectedData data = pipeline.collect(SenseChannel.ChannelType.VISUAL, request());
        assertTrue(data.isDegraded());
        assertEquals("TOUCH", data.getSourceChannel());
        assertEquals(CollectedData.StagingStatus.ACCEPTED, data.getStagingStatus());
        assertEquals(0, visual.calls());
        assertEquals(0, audio.calls());
        assertEquals(0, nose.calls());
        assertEquals(0, taste.calls());
    }

    @Test
    void noAvailableChannelFailsFastAndRegistersDeadLetter() {
        ScriptedChannel visual = new ScriptedChannel(SenseChannel.ChannelType.VISUAL, 0, "");
        visual.setAvailable(false);
        CollectPipeline pipeline = pipeline(List.of(visual));
        CollectedData data = pipeline.collect(SenseChannel.ChannelType.VISUAL, request());
        assertEquals(CollectedData.StagingStatus.REJECTED, data.getStagingStatus());
        assertTrue(data.getRejectReason().contains("no available channel"));
        assertEquals(1, deadLetterStore.size());
    }

    @Test
    void acceptedDataIsStagedAndCountedInMetrics() {
        ScriptedChannel touch = new ScriptedChannel(SenseChannel.ChannelType.TOUCH, 0,
                "一条通过质检并进入隔离暂存区的采集正文内容，用于指标统计校验。");
        CollectPipeline pipeline = pipeline(List.of(touch));
        CollectedData data = pipeline.collect(SenseChannel.ChannelType.TOUCH, request());
        assertNotNull(data.getStagingRef());
        assertTrue(data.getStagingRef().startsWith("local://"));
        assertEquals(1, metrics.counter(SenseChannel.ChannelType.TOUCH).accepted());
        assertEquals(100.0, metrics.overallQualityPassRate());
    }
}
