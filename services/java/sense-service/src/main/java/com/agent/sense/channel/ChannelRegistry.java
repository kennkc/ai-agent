package com.agent.sense.channel;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 渠道注册表（R2-01）
 * 五感官渠道以 Map<ChannelType, SenseChannel> 注册；新增渠道只需实现 SenseChannel 并声明为 Bean，
 * 核心采集流程无需改动（渠道可插拔）。
 */
@Component
public class ChannelRegistry {

    private final Map<SenseChannel.ChannelType, SenseChannel> channels =
            Collections.synchronizedMap(new EnumMap<>(SenseChannel.ChannelType.class));

    /** Spring 自动注入全部 SenseChannel 实现 */
    public ChannelRegistry(List<SenseChannel> beans) {
        for (SenseChannel channel : beans) {
            channels.put(channel.type(), channel);
            channel.register(Map.of());
        }
    }

    public Optional<SenseChannel> find(SenseChannel.ChannelType type) {
        return Optional.ofNullable(channels.get(type));
    }

    public SenseChannel require(SenseChannel.ChannelType type) {
        return find(type).orElseThrow(() -> new IllegalStateException("channel not registered: " + type));
    }

    public Collection<SenseChannel> all() { return List.copyOf(channels.values()); }

    public List<SenseChannel.ChannelType> types() {
        List<SenseChannel.ChannelType> out = new ArrayList<>(channels.keySet());
        return out;
    }

    /** 已实现（非预留）且注册的渠道 */
    public List<SenseChannel> available() {
        List<SenseChannel> out = new ArrayList<>();
        for (SenseChannel channel : channels.values()) if (channel.available()) out.add(channel);
        return out;
    }

    /** 渠道健康快照 */
    public Map<SenseChannel.ChannelType, Boolean> healthSnapshot() {
        Map<SenseChannel.ChannelType, Boolean> snapshot = new EnumMap<>(SenseChannel.ChannelType.class);
        channels.forEach((type, channel) -> {
            boolean ok;
            try { ok = channel.healthy(); } catch (Exception e) { ok = false; }
            snapshot.put(type, ok);
        });
        return snapshot;
    }

    public int size() { return channels.size(); }
}
