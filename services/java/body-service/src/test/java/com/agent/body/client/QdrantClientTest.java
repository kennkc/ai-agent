package com.agent.body.client;

import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Qdrant point id 约束回归（R3-04）
 *
 * <p>背景：Qdrant 只接受「无符号整数或 UUID」作为 point id。E2E 实测中把业务 chunk_id
 * （{@code phase3-arch#0}）直接当 id 会被 400 拒绝，入库整体失败。此处把约束固化为用例。
 */
class QdrantClientTest {

    private static final Pattern UUID_LIKE =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    @Test
    void pointIdIsUuidShaped() {
        assertTrue(UUID_LIKE.matcher(QdrantClient.pointId("phase3-arch#0")).matches());
        assertTrue(UUID_LIKE.matcher(QdrantClient.pointId("中文文档#12")).matches());
        assertTrue(UUID_LIKE.matcher(QdrantClient.pointId("")).matches());
    }

    @Test
    void pointIdIsDeterministicSoReingestIsIdempotent() {
        assertEquals(QdrantClient.pointId("doc-1#0"), QdrantClient.pointId("doc-1#0"));
        assertEquals(QdrantClient.pointId("doc-1#0"), QdrantClient.pointId("doc-1#0"));
    }

    @Test
    void differentChunksGetDifferentPointIds() {
        assertNotEquals(QdrantClient.pointId("doc-1#0"), QdrantClient.pointId("doc-1#1"));
        assertNotEquals(QdrantClient.pointId("doc-1#0"), QdrantClient.pointId("doc-2#0"));
    }
}
