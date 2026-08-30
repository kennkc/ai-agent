package com.agent.sense.nats;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极简 NATS 协议客户端（R1-04 总线应答）
 * 直接实现 NATS core 协议（TCP + 帧解析），规避 jnats 在 Spring Boot fat jar
 * 环境下消息读取线程失效的问题。支持：SUB 通配订阅、MSG 解析、PUB 应答、PING/PONG。
 */
public class MinimalNatsClient {

    private static final Logger log = LoggerFactory.getLogger(MinimalNatsClient.class);

    private final String host;
    private final int port;
    private final String clientName;
    private final MessageHandler handler;

    private Socket socket;
    private BufferedWriter writer;
    private volatile boolean running = true;
    private ExecutorService executor;

    public interface MessageHandler {
        void onMessage(String subject, String replyTo, String payload);
    }

    public MinimalNatsClient(String host, int port, String clientName, MessageHandler handler) {
        this.host = host;
        this.port = port;
        this.clientName = clientName;
        this.handler = handler;
    }

    /** 建立连接并启动读循环（阻塞调用方直到连接完成） */
    public void connect() throws Exception {
        socket = new Socket(host, port);
        socket.setSoTimeout(5000);
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        // NATS 协议握手
        writer.write("CONNECT {\"verbose\":false,\"pedantic\":false,\"name\":\"" + clientName + "\"}\r\n");
        writer.write("PING\r\n");
        writer.flush();
        // 读 INFO + PONG
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        reader.readLine(); // INFO {...}
        String line = reader.readLine(); // PING 或 PONG
        while (line != null && line.startsWith("PING")) {
            writer.write("PONG\r\n");
            writer.flush();
            line = reader.readLine();
        }
        // 启动后台读循环
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "nats-minimal-reader");
            t.setDaemon(true);
            return t;
        });
        executor.submit(() -> readLoop(reader));
        log.info("MinimalNatsClient 已连接 {}:{} 并启动读循环", host, port);
    }

    /** 订阅主题（通配支持） */
    public void subscribe(String subject) throws Exception {
        writer.write("SUB " + subject + " 1\r\n");
        writer.flush();
        log.info("已订阅: {}", subject);
    }

    /** 发布消息（应答） */
    public void publish(String subject, String payload) throws Exception {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        synchronized (writer) {
            writer.write("PUB " + subject + " " + bytes.length + "\r\n");
            writer.write(payload);
            writer.write("\r\n");
            writer.flush();
        }
    }

    /** 读循环：解析 MSG/PING 帧 */
    private void readLoop(BufferedReader reader) {
        while (running) {
            try {
                String line = reader.readLine();
                if (line == null) {
                    log.warn("NATS 连接关闭（EOF）");
                    break;
                }
                if (line.startsWith("PING")) {
                    synchronized (writer) {
                        writer.write("PONG\r\n");
                        writer.flush();
                    }
                    continue;
                }
                if (line.startsWith("MSG ")) {
                    // MSG <subject> <sid> [reply-to] <size>
                    String[] parts = line.split(" ");
                    String subject = parts[1];
                    int size = Integer.parseInt(parts[parts.length - 1]);
                    String replyTo = parts.length > 4 ? parts[3] : null;
                    char[] buf = new char[size];
                    int off = 0;
                    while (off < size) {
                        int n = reader.read(buf, off, size - off);
                        if (n < 0) break;
                        off += n;
                    }
                    reader.readLine(); // 吃掉 payload 后的 \r\n
                    String payload = new String(buf, 0, off);
                    log.info("[总线] 收到 {} (reply={}): {}", subject, replyTo, payload);
                    if (handler != null) {
                        handler.onMessage(subject, replyTo, payload);
                    }
                }
            } catch (Exception e) {
                if (running) {
                    log.warn("总线读取异常: {}", e.getMessage());
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                }
            }
        }
    }

    public void close() {
        running = false;
        if (executor != null) executor.shutdownNow();
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {}
    }
}
