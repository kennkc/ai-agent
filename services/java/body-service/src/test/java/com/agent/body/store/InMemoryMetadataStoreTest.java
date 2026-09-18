package com.agent.body.store;

import com.agent.body.common.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R3-01 冷层语义验收：元数据为真相源、覆盖式写入（重入库不残留）、租户隔离。
 */
class InMemoryMetadataStoreTest {

    private StoredDocument document(String docId, String tenantId, DocumentStatus status, int chunks) {
        long now = System.currentTimeMillis();
        return new StoredDocument(docId, tenantId, "标题-" + docId, "manual", 120, chunks, status,
                "hash-ngram-768", now, now, null);
    }

    private StoredChunk chunk(String docId, String tenantId, int index) {
        return new StoredChunk(docId + "#" + index, docId, tenantId, index, "小节", "正文" + index, 10,
                "hash-ngram-768", System.currentTimeMillis());
    }

    @Test
    void savingSameDocumentOverwritesInsteadOfDuplicating() {
        InMemoryMetadataStore store = new InMemoryMetadataStore();
        store.saveDocument(document("d1", "t1", DocumentStatus.PENDING, 0));
        store.saveDocument(document("d1", "t1", DocumentStatus.INDEXED, 3));
        assertEquals(1, store.countDocuments("t1"));
        assertEquals(DocumentStatus.INDEXED, store.findDocument("t1", "d1").status());
    }

    @Test
    void replacingChunksRemovesStaleOnes() {
        InMemoryMetadataStore store = new InMemoryMetadataStore();
        store.replaceChunks("t1", "d1", List.of(chunk("d1", "t1", 0), chunk("d1", "t1", 1), chunk("d1", "t1", 2)));
        assertEquals(3, store.countChunks("t1"));
        // 重入库只剩 1 块 → 旧切片必须清空
        store.replaceChunks("t1", "d1", List.of(chunk("d1", "t1", 0)));
        assertEquals(1, store.countChunks("t1"));
    }

    @Test
    void tenantsAreIsolated() {
        InMemoryMetadataStore store = new InMemoryMetadataStore();
        store.saveDocument(document("d1", "t1", DocumentStatus.INDEXED, 1));
        store.saveDocument(document("d1", "t2", DocumentStatus.INDEXED, 2));
        assertEquals(1, store.countDocuments("t1"));
        assertEquals(1, store.countDocuments("t2"));
        assertEquals(2, store.findDocument("t2", "d1").chunkCount());
        // 跨租户按 docId 读取必须落到各自租户的记录
        assertNull(store.findDocument("t3", "d1"));
    }

    @Test
    void deletingMissingDocumentRaisesNotFound() {
        InMemoryMetadataStore store = new InMemoryMetadataStore();
        assertThrows(BizException.class, () -> store.deleteDocument("t1", "missing"));
    }

    @Test
    void backendIsReportedAsMemory() {
        assertEquals("memory", new InMemoryMetadataStore().backend());
    }

    @Test
    void documentsAreListedNewestFirst() {
        InMemoryMetadataStore store = new InMemoryMetadataStore();
        store.saveDocument(new StoredDocument("old", "t1", "旧", "manual", 10, 1, DocumentStatus.INDEXED,
                "hash-ngram-768", 1000, 1000, null));
        store.saveDocument(new StoredDocument("new", "t1", "新", "manual", 10, 1, DocumentStatus.INDEXED,
                "hash-ngram-768", 2000, 2000, null));
        List<StoredDocument> documents = store.listDocuments("t1", 10);
        assertEquals("new", documents.get(0).docId());
    }
}
