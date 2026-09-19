package com.agent.tool.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Docker 沙箱后端（R5-03 主路径）。
 *
 * <p>隔离参数（对应开发设计 §3.2「只读 rootfs + 网络禁用 + 资源限制」）：
 * <pre>
 *   docker run --rm -i \
 *     --network none \                 # 网络禁用（R5-03 验收"网络访问被拦"）
 *     --memory 256m --memory-swap 256m # 内存硬限
 *     --cpus 0.5                       # CPU 限
 *     --pids-limit 64                  # 防 fork 炸弹
 *     --read-only --tmpfs /tmp:rw,size=16m  # 只读 rootfs + 可写 tmpfs
 *     --user 1000:1000                 # 非 root
 *     --cap-drop ALL --security-opt no-new-privileges  # 去能力 + 禁提权
 *     agent-sandbox:latest python3 -
 * </pre>
 *
 * <p>镜像由 {@code infra/docker/sandbox/Dockerfile} 构建；镜像缺失或无 Docker 时
 * 本后端 {@code available()=false}，由 {@link SandboxExecutor} 落到受限子进程并标降级。
 *
 * <p><b>探测策略（2026-09-19 修复）</b>：{@code docker info} + {@code image inspect} 在
 * Windows Docker Desktop 上单次冷探测约需 5-6s。若在请求线程内同步探测，上游
 * （wp-bff 对工具域子请求的预算仅 2.5s）会超时并误报 {@code sandbox_unavailable} ——
 * 端点"看着不可用"其实是"回得太慢"。故：
 * <ul>
 *   <li>{@link #available()} <b>恒不阻塞</b>：只返回缓存值，过期时投递一次后台刷新；</li>
 *   <li>启动时同步探一次，使启动日志与首个请求都能拿到真实结论；</li>
 *   <li>同一时刻至多一个刷新在飞（{@code refreshing} 门闩），避免探针风暴。</li>
 * </ul>
 */
@Component
public class DockerSandboxBackend implements SandboxBackend {
    private static final Logger log = LoggerFactory.getLogger(DockerSandboxBackend.class);

    private final boolean enabled;
    private final String image;
    private final String pythonInImage;
    private final int memoryMb;
    /** 探测实现（可注入，测试用；生产走 docker CLI）。 */
    private final BooleanSupplier prober;
    private final long probeTtlMs;
    private final AtomicLong lastProbeAt = new AtomicLong(0);
    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private volatile boolean lastProbeResult = false;
    private static final long PROBE_TTL_MS = 15_000;

    @Autowired
    public DockerSandboxBackend(
            @Value("${app.tool.sandbox.docker.enabled:true}") boolean enabled,
            @Value("${app.tool.sandbox.docker.image:agent-sandbox:latest}") String image,
            @Value("${app.tool.sandbox.docker.python:python3}") String pythonInImage,
            @Value("${app.tool.sandbox.memory-mb:256}") int memoryMb) {
        this(enabled, image, pythonInImage, memoryMb, () -> probeDockerCli(image), PROBE_TTL_MS);
    }

    /**
     * 测试专用构造：注入探测函数与 TTL。
     * 用于守卫「**探测再慢，{@code available()} 也不得阻塞请求线程**」这一契约 ——
     * 2026-09-19 的 {@code sandbox_unavailable} 误报正是该契约缺失造成的。
     */
    DockerSandboxBackend(boolean enabled, String image, String pythonInImage, int memoryMb,
                         BooleanSupplier prober, long probeTtlMs) {
        this.enabled = enabled;
        this.image = image;
        this.pythonInImage = pythonInImage;
        this.memoryMb = memoryMb;
        this.prober = prober;
        this.probeTtlMs = probeTtlMs;
        if (enabled) {
            // 启动时同步探一次（仅此一次），把"沙箱是否真可用"写进日志，避免运行期静默降级
            doProbe();
            log.info("Docker 沙箱探测：available={} image={}（不可用时将降级为受限子进程）",
                    lastProbeResult, image);
        }
    }

    /** 真实探测：docker CLI 可用 且 镜像存在。 */
    private static boolean probeDockerCli(String image) {
        try {
            ProcessRunner.Outcome probe =
                    ProcessRunner.probe(List.of("docker", "info", "--format", "{{.ServerVersion}}"), 5000);
            if (!probe.ok()) return false;
            // 镜像是否存在也要确认，否则每次执行都要浪费一个启动周期才降级
            return ProcessRunner.probe(List.of("docker", "image", "inspect", image), 5000).ok();
        } catch (Exception e) {
            return false;
        }
    }

    @Override public String name() { return "docker"; }
    @Override public boolean isolated() { return true; }

    /**
     * 沙箱是否可用 —— <b>非阻塞</b>。返回最近一次探测的缓存结论；若缓存过期，
     * 投递一次后台刷新后立即返回。<b>绝不在请求线程内执行 docker CLI</b>。
     */
    @Override public boolean available() {
        if (!enabled) return false;
        if (System.currentTimeMillis() - lastProbeAt.get() >= probeTtlMs
                && refreshing.compareAndSet(false, true)) {
            Thread probe = new Thread(() -> {
                try {
                    doProbe();
                } finally {
                    refreshing.set(false);
                }
            }, "docker-sandbox-probe");
            probe.setDaemon(true);
            probe.start();
        }
        return lastProbeResult;
    }

    /** 同步执行一次探测并落缓存（启动路径与后台刷新共用）。 */
    private void doProbe() {
        boolean ok = prober.getAsBoolean();
        lastProbeAt.set(System.currentTimeMillis());
        lastProbeResult = ok;
    }

    @Override
    public SandboxResult run(SandboxSpec spec) {
        List<String> command = new ArrayList<>(List.of(
                "docker", "run", "--rm", "-i",
                "--network", spec.networkEnabled() ? "bridge" : "none",
                "--memory", spec.memoryMb() + "m",
                "--memory-swap", spec.memoryMb() + "m",
                "--cpus", "0.5",
                "--pids-limit", "64",
                "--read-only",
                "--tmpfs", "/tmp:rw,size=16m",
                "--user", "1000:1000",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                image,
                pythonInImage, "-"));
        ProcessRunner.Outcome outcome = ProcessRunner.run(command, spec.code(), spec.timeoutMs());
        if (outcome.timedOut()) {
            return new SandboxResult(false, outcome.stdout(), outcome.stderr(), -1, name(), false, false,
                    "沙箱执行超时已强制终止：" + spec.timeoutMs() + "ms");
        }
        if (outcome.error() != null) {
            return new SandboxResult(false, outcome.stdout(), outcome.stderr(), -1, name(), false, false,
                    "沙箱启动失败：" + outcome.error());
        }
        return new SandboxResult(outcome.exitCode() == 0, outcome.stdout(), outcome.stderr(),
                outcome.exitCode(), name(), false, false, null);
    }
}