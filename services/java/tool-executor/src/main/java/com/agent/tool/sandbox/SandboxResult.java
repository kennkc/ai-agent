package com.agent.tool.sandbox;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 沙箱执行结果。
 *
 * @param backend  实际执行后端（docker / process-restricted）
 * @param degraded **是否非隔离边界**。{@code docker} 为真隔离，{@code degraded=false}；
 *                 {@code process-restricted} 只是受限子进程，**不是隔离边界**，恒为 {@code true}。
 *                 视图层据此显示"沙箱降级"徽标，不把受限子进程冒充成沙箱。
 * @param rejected 是否在执行前被拒（静态预检 / 沙箱策略）
 */
public record SandboxResult(boolean success, String stdout, String stderr, int exitCode,
                            String backend, boolean degraded, boolean rejected, String note) {

    public static SandboxResult rejected(String reason) {
        return new SandboxResult(false, null, reason, -1, "none", false, true, reason);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("success", success);
        row.put("stdout", stdout);
        row.put("stderr", stderr);
        row.put("exit_code", exitCode);
        row.put("sandbox_backend", backend);
        row.put("degraded", degraded);
        row.put("rejected", rejected);
        row.put("note", note);
        return row;
    }
}