package com.agent.tool.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具原生执行结果（未标准化）。
 *
 * <p>工具实现只需关心"我成功了没有 + 输出是什么"；耗时、沙箱后端、审计 ID
 * 等横切信息由 {@code ToolExecutor} 统一补齐（R5-02「结果标准化」）。
 *
 * <p><b>容忍 null 值</b>：失败路径上 stdout/stderr 常常为 null，若用不可变集合的
 * 严格复制会直接抛 NPE —— 那等于"报错时报错"，最不该发生的地方（该坑已在单测中回归）。
 */
public final class ToolOutcome {

    private final boolean success;
    private final String output;
    private final String errorCode;
    private final String errorMessage;
    private final Map<String, Object> data;

    private ToolOutcome(boolean success, String output, String errorCode, String errorMessage, Map<String, Object> data) {
        this.success = success;
        this.output = output;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.data = data == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    public static ToolOutcome ok(String output) { return new ToolOutcome(true, output, null, null, Map.of()); }

    public static ToolOutcome ok(String output, Map<String, Object> data) {
        return new ToolOutcome(true, output, null, null, data);
    }

    public static ToolOutcome fail(String errorCode, String errorMessage) {
        return new ToolOutcome(false, null, errorCode, errorMessage, Map.of());
    }

    public static ToolOutcome fail(String errorCode, String errorMessage, Map<String, Object> data) {
        return new ToolOutcome(false, null, errorCode, errorMessage, data);
    }

    public boolean success() { return success; }
    public String output() { return output; }
    public String errorCode() { return errorCode; }
    public String errorMessage() { return errorMessage; }
    public Map<String, Object> data() { return data; }

    public Map<String, Object> toMap() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("success", success);
        row.put("output", output);
        row.put("error_code", errorCode);
        row.put("error_message", errorMessage);
        if (!data.isEmpty()) row.put("data", data);
        return row;
    }
}