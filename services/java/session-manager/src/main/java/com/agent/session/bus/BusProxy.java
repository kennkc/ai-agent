package com.agent.session.bus;

import com.agent.session.nats.NatsClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class BusProxy {
    private final NatsClient natsClient;
    private final ObjectMapper objectMapper;

    public BusProxy(NatsClient natsClient, ObjectMapper objectMapper) {
        this.natsClient = natsClient;
        this.objectMapper = objectMapper;
    }

    public String request(String subject, Map<String, Object> payload) {
        try {
            return natsClient.request(subject, objectMapper.writeValueAsString(payload == null ? Map.of() : payload));
        } catch (Exception e) {
            return null;
        }
    }

    public void publish(String subject, Map<String, Object> payload) {
        try {
            natsClient.publish(subject, objectMapper.writeValueAsString(payload == null ? Map.of() : payload));
        } catch (Exception ignored) {
            // Bus delivery is best-effort in Phase 1; Kafka remains the durable event channel.
        }
    }
}
