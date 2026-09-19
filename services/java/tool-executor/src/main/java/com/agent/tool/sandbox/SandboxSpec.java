package com.agent.tool.sandbox;

import java.util.List;

/**
 * 沙箱执行请求（R5-03 / R5-05）。
 *
 * @param language  运行语言（python / shell）
 * @param code      待执行代码
 * @param timeoutMs 硬超时
 * @param memoryMb  内存上限
 * @param networkEnabled 是否允许出网（默认 false —— 只有显式声明才放开）
 */
public record SandboxSpec(String language, String code, int timeoutMs, int memoryMb, boolean networkEnabled) {

    public SandboxSpec {
        if (language == null || language.isBlank()) language = "python";
        if (timeoutMs <= 0) timeoutMs = 10_000;
        if (memoryMb <= 0) memoryMb = 256;
    }

    public static SandboxSpec python(String code, int timeoutMs, int memoryMb) {
        return new SandboxSpec("python", code, timeoutMs, memoryMb, false);
    }

    public List<String> describe() {
        return List.of("language=" + language, "timeout_ms=" + timeoutMs,
                "memory_mb=" + memoryMb, "network=" + networkEnabled);
    }
}