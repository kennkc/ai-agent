package com.agent.tool.grpc;

import io.grpc.Server;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * gRPC health server（与四服务同构）。
 *
 * <p><b>端口 9095</b>：Phase 5 起四肢层占用。注意本机 9092=Kafka、9093=Alertmanager(P7)，
 * 而 9094=body-service —— 9095 是本阶段新分配、当前无冲突的端口。
 * （sense-service 9093 与 Alertmanager 的潜在冲突见 DEBT-018。）
 */
@Component
public class GrpcHealthServer implements SmartLifecycle {
    private final int port;
    private Server server;

    public GrpcHealthServer(@Value("${grpc.port:0}") int port) { this.port = port; }

    @Override public void start() {
        if (port <= 0) return;
        try {
            HealthStatusManager health = new HealthStatusManager();
            health.setStatus("", ServingStatus.SERVING);
            server = NettyServerBuilder.forPort(port).addService(health.getHealthService()).build().start();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start gRPC health server on port " + port, e);
        }
    }

    @Override public void stop() { if (server != null) server.shutdownNow(); }
    @Override public boolean isRunning() { return server != null && !server.isShutdown(); }
}