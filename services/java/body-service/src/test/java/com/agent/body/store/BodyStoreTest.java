package com.agent.body.store;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BodyStoreTest {
    @Test
    void retrievesWithinTenantOnly() {
        BodyStore store = new BodyStore();
        assertTrue(store.ingest("tenant-a", "doc-1", "架构说明", "agent gateway session knowledge retrieval", "test") > 0);
        assertFalse(store.retrieve("tenant-a", "knowledge", 3).isEmpty());
        assertTrue(store.retrieve("tenant-b", "knowledge", 3).isEmpty());
    }
}
