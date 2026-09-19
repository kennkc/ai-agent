package com.agent.tool.model;

/** 注册表条目：元数据 + 实现绑定 */
public record ToolDefinition(ToolMeta meta, ToolHandler handler, long registeredAt) {

    public static ToolDefinition bind(ToolMeta meta, ToolHandler handler) {
        return new ToolDefinition(meta, handler, System.currentTimeMillis());
    }
}