package com.agent.sense.channel;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TouchChannelTest {
    @Test
    void blocksLoopbackUrl() {
        TouchChannel channel = new TouchChannel();
        SenseChannel.CollectRequest request = new SenseChannel.CollectRequest();
        request.setDataSource("http://127.0.0.1:8080/secret");
        assertFalse(channel.collect(request).isAccepted());
    }

    @Test
    void acceptsPlainText() {
        TouchChannel channel = new TouchChannel();
        SenseChannel.CollectRequest request = new SenseChannel.CollectRequest();
        request.setDataSource("hello knowledge");
        SenseChannel.CollectResult result = channel.collect(request);
        assertTrue(result.isAccepted());
        assertEquals("hello knowledge", result.getContent());
    }
}
