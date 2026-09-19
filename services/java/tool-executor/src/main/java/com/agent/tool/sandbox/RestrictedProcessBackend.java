package com.agent.tool.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 受限子进程后端（**降级路径，不是隔离边界**）。
 *
 * <p><b>诚实定性</b>：本后端在 Docker 不可达时启用，它**做不到**真正的隔离——
 * 无法做到 rootfs 只读、无法真正断网、无法限制 pids。它只提供三件事：
 * <ol>
 *   <li>代码不能直接内联在 shell 里（写入临时文件后以独立进程执行，走 {@code python -}）；</li>
 *   <li>工作目录隔离到每次调用独有的临时目录，执行后尽力清理；</li>
 *   <li>硬超时 + 强制终止（与 Docker 后端同一套 {@link ProcessRunner}）。</li>
 * </ol>
 * 因此其结果**恒带 {@code degraded=true}**，且沙箱安全闸（静态预检）仍在外层先行拦截。
 * <b>绝不把本后端冒充成沙箱</b>：视图层凭 {@code sandbox_backend=process-restricted} +
 * {@code degraded=true} 显示降级徽标。
 */
@Component
public class RestrictedProcessBackend implements SandboxBackend {
    private static final Logger log = LoggerFactory.getLogger(RestrictedProcessBackend.class);

    private final String pythonBin;
    private final boolean enabled;

    public RestrictedProcessBackend(
            @Value("${app.tool.sandbox.process.fallback-enabled:true}") boolean enabled,
            @Value("${app.tool.sandbox.process.python-bin:python}") String pythonBin) {
        this.enabled = enabled;
        this.pythonBin = pythonBin;
    }

    @Override public String name() { return "process-restricted"; }
    @Override public boolean isolated() { return false; }
    @Override public boolean available() { return enabled; }

    @Override
    public SandboxResult run(SandboxSpec spec) {
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("tool-sandbox-");
            Path script = workDir.resolve("main.py");
            Files.writeString(script, spec.code() == null ? "" : spec.code(), StandardCharsets.UTF_8);

            // 通过 stdin 交付源码，避免命令行长度限制与转义问题
            ProcessRunner.Outcome outcome = ProcessRunner.run(
                    List.of(pythonBin, "-"), spec.code(), spec.timeoutMs());
            if (outcome.timedOut()) {
                return new SandboxResult(false, outcome.stdout(), outcome.stderr(), -1, name(), true, false,
                        "降级后端执行超时已强制终止：" + spec.timeoutMs() + "ms");
            }
            if (outcome.error() != null) {
                return new SandboxResult(false, outcome.stdout(), outcome.stderr(), -1, name(), true, false,
                        "降级后端启动失败：" + outcome.error());
            }
            return new SandboxResult(outcome.exitCode() == 0, outcome.stdout(), outcome.stderr(),
                    outcome.exitCode(), name(), true, false,
                    "非隔离边界：仅受限子进程 + 静态预检，不等价于沙箱");
        } catch (Exception e) {
            log.warn("受限后端执行异常", e);
            return new SandboxResult(false, null, String.valueOf(e.getMessage()), -1, name(), true, false,
                    "降级后端异常");
        } finally {
            if (workDir != null) {
                try (var paths = Files.walk(workDir)) {
                    paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                        try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                    });
                } catch (Exception ignored) { }
            }
        }
    }

    /** 供健康检查展示的后端自述 */
    public Map<String, Object> describe() {
        return Map.of("backend", name(), "isolated", false, "enabled", enabled, "python_bin", pythonBin);
    }
}