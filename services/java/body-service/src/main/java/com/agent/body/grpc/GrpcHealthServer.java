package com.agent.body.grpc;

import io.grpc.Server;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.services.HealthStatusManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
public class GrpcHealthServer implements SmartLifecycle {
    private final int port;
    private Server server;
    public GrpcHealthServer(@Value("${grpc.port:0}") int port) { this.port = port; }
    @Override public void start() {
        if (port <= 0) return;
        try { HealthStatusManager health = new HealthStatusManager(); health.setStatus("", ServingStatus.SERVING);
            server = NettyServerBuilder.forPort(port).addService(health.getHealthService()).build().start();
        } catch (Exception e) { throw new IllegalStateException("Failed to start gRPC health server on port " + port, e); }
    }
    @Override public void stop() { if (server != null) server.shutdownNow(); }
    @Override public boolean isRunning() { return server != null && !server.isShutdown(); }
}
