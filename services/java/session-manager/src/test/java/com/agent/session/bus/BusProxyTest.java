package com.agent.session.bus;

import com.agent.session.nats.NatsClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BusProxyTest {
    @Test
    void serializesAndDelegatesRequest() {
        NatsClient nats = mock(NatsClient.class);
        when(nats.request(eq("lifeform.rpc.sense.ping"), contains("tenant-a"))).thenReturn("pong");
        BusProxy bus = new BusProxy(nats, new ObjectMapper());
        assertEquals("pong", bus.request("lifeform.rpc.sense.ping", Map.of("tenant_id", "tenant-a")));
    }
}
