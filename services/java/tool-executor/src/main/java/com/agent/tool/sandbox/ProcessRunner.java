package com.agent.tool.sandbox;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 子进程执行助手：**带硬超时**、并发排空 stdout/stderr、超时强杀。
 *
 * <p>沙箱与降级后端共用，避免两处各写一份超时逻辑（R5-02「超时熔断」的一致性前提）。
 */
public final class ProcessRunner {

    private ProcessRunner() { }

    public record Outcome(int exitCode, String stdout, String stderr, boolean timedOut, long elapsedMs, String error) {
        public boolean ok() { return !timedOut && error == null && exitCode == 0; }
    }

    public static Outcome run(List<String> command, String stdin, int timeoutMs) {
        long started = System.currentTimeMillis();
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(false).start();
            if (stdin != null) {
                try (OutputStream out = process.getOutputStream()) {
                    out.write(stdin.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            }
            // 并发读取，避免管道写满导致子进程阻塞假死
            StreamPump stdoutPump = new StreamPump(process.getInputStream());
            StreamPump stderrPump = new StreamPump(process.getErrorStream());
            Thread outThread = new Thread(stdoutPump, "sandbox-stdout");
            Thread errThread = new Thread(stderrPump, "sandbox-stderr");
            outThread.setDaemon(true);
            errThread.setDaemon(true);
            outThread.start();
            errThread.start();

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - started;
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                outThread.join(500);
                errThread.join(500);
                return new Outcome(-1, stdoutPump.text(), stderrPump.text(), true, elapsed,
                        "沙箱执行超时（" + timeoutMs + "ms）已强制终止");
            }
            outThread.join(500);
            errThread.join(500);
            return new Outcome(process.exitValue(), stdoutPump.text(), stderrPump.text(), false, elapsed, null);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - started;
            if (process != null && process.isAlive()) process.destroyForcibly();
            return new Outcome(-1, null, null, false, elapsed, String.valueOf(e.getMessage()));
        }
    }

    /** 单纯探活（不读 stdin）：如 `docker info` */
    public static Outcome probe(List<String> command, int timeoutMs) {
        return run(command, null, timeoutMs);
    }

    private static final class StreamPump implements Runnable {
        private final InputStream stream;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        private StreamPump(InputStream stream) { this.stream = stream; }

        @Override public void run() {
            byte[] chunk = new byte[4096];
            try {
                int read;
                while ((read = stream.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
            } catch (Exception ignored) {
                // 子进程被杀时读端会异常，忽略即可
            }
        }

        private String text() {
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}