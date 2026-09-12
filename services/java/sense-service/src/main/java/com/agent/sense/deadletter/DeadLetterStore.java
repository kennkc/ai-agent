package com.agent.sense.deadletter;

import com.agent.sense.channel.SenseChannel;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 死信队列（R2-09）：重试耗尽后登记的采集失败记录，供人工/后续阶段补偿。
 * 采用有界内存队列（MAX_RECORDS 条），避免无界增长。
 */
@Component
public class DeadLetterStore {

    private static final int MAX_RECORDS = 500;

    private final Deque<Record> records = new ArrayDeque<>();
    private final AtomicLong total = new AtomicLong();

    public String add(SenseChannel.ChannelType channel, String tenantId, String dataSource,
                      String error, int attempts, String mode) {
        Record record = new Record();
        record.setId(UUID.randomUUID().toString());
        record.setChannel(channel == null ? "UNKNOWN" : channel.name());
        record.setTenantId(tenantId);
        record.setDataSource(abbreviate(dataSource));
        record.setError(abbreviate(error));
        record.setAttempts(attempts);
        record.setMode(mode);
        record.setCreatedAt(System.currentTimeMillis());
        synchronized (records) {
            if (records.size() >= MAX_RECORDS) records.removeFirst();
            records.addLast(record);
        }
        total.incrementAndGet();
        return record.getId();
    }

    public List<Record> list(int limit) {
        synchronized (records) {
            List<Record> out = new ArrayList<>(records);
            java.util.Collections.reverse(out);
            return limit > 0 && out.size() > limit ? out.subList(0, limit) : out;
        }
    }

    public int size() {
        synchronized (records) { return records.size(); }
    }

    public long total() { return total.get(); }

    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("pending", size());
        stats.put("total", total.get());
        return stats;
    }

    private static String abbreviate(String value) {
        if (value == null) return null;
        return value.length() > 300 ? value.substring(0, 300) + "..." : value;
    }

    @Data
    public static class Record {
        private String id;
        private String channel;
        private String tenantId;
        private String dataSource;
        private String error;
        private int attempts;
        private String mode;
        private long createdAt;
    }
}
