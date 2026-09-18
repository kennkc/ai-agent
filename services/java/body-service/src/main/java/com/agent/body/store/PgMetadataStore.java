package com.agent.body.store;

import com.agent.body.common.BizException;
import com.agent.body.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

/**
 * PG 元数据存储（R3-01 冷层实现）：schema 自举 + 覆盖式写入。
 *
 * <p>表结构：{@code knowledge_document}（文档真相源）+ {@code knowledge_chunk}（切片明细）。
 * DDL 用 {@code CREATE TABLE IF NOT EXISTS} 自举，避免引入迁移框架（后续阶段可换 Flyway）。
 */
public class PgMetadataStore implements MetadataStore {

    private static final Logger log = LoggerFactory.getLogger(PgMetadataStore.class);

    private static final String DDL_DOCUMENT = """
            CREATE TABLE IF NOT EXISTS knowledge_document (
                doc_id VARCHAR(64) PRIMARY KEY,
                tenant_id VARCHAR(64) NOT NULL,
                title VARCHAR(512),
                source VARCHAR(256),
                char_count INT DEFAULT 0,
                chunk_count INT DEFAULT 0,
                status VARCHAR(32) NOT NULL,
                backend VARCHAR(64),
                created_at BIGINT,
                updated_at BIGINT,
                error VARCHAR(1024)
            )
            """;

    private static final String DDL_DOCUMENT_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_knowledge_document_tenant ON knowledge_document(tenant_id, updated_at DESC)";

    private static final String DDL_CHUNK = """
            CREATE TABLE IF NOT EXISTS knowledge_chunk (
                chunk_id VARCHAR(96) PRIMARY KEY,
                doc_id VARCHAR(64) NOT NULL,
                tenant_id VARCHAR(64) NOT NULL,
                chunk_index INT,
                heading VARCHAR(512),
                content TEXT,
                char_count INT,
                vector_backend VARCHAR(64),
                created_at BIGINT
            )
            """;

    private static final String DDL_CHUNK_INDEX =
            "CREATE INDEX IF NOT EXISTS idx_knowledge_chunk_tenant ON knowledge_chunk(tenant_id, doc_id, chunk_index)";

    private final JdbcTemplate jdbc;

    private final RowMapper<StoredDocument> documentMapper = (rs, rowNum) -> new StoredDocument(
            rs.getString("doc_id"), rs.getString("tenant_id"), rs.getString("title"), rs.getString("source"),
            rs.getInt("char_count"), rs.getInt("chunk_count"),
            DocumentStatus.valueOf(rs.getString("status")), rs.getString("backend"),
            rs.getLong("created_at"), rs.getLong("updated_at"), rs.getString("error"));

    private final RowMapper<StoredChunk> chunkMapper = (rs, rowNum) -> new StoredChunk(
            rs.getString("chunk_id"), rs.getString("doc_id"), rs.getString("tenant_id"),
            rs.getInt("chunk_index"), rs.getString("heading"), rs.getString("content"),
            rs.getInt("char_count"), rs.getString("vector_backend"), rs.getLong("created_at"));

    public PgMetadataStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute(DDL_DOCUMENT);
        jdbc.execute(DDL_DOCUMENT_INDEX);
        jdbc.execute(DDL_CHUNK);
        jdbc.execute(DDL_CHUNK_INDEX);
        log.info("PG 元数据存储就绪（knowledge_document / knowledge_chunk）");
    }

    @Override
    public void saveDocument(StoredDocument document) {
        jdbc.update("""
                INSERT INTO knowledge_document (doc_id, tenant_id, title, source, char_count, chunk_count,
                                                status, backend, created_at, updated_at, error)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (doc_id) DO UPDATE SET
                    tenant_id = EXCLUDED.tenant_id, title = EXCLUDED.title, source = EXCLUDED.source,
                    char_count = EXCLUDED.char_count, chunk_count = EXCLUDED.chunk_count,
                    status = EXCLUDED.status, backend = EXCLUDED.backend,
                    updated_at = EXCLUDED.updated_at, error = EXCLUDED.error
                """,
                document.docId(), document.tenantId(), document.title(), document.source(),
                document.charCount(), document.chunkCount(), document.status().name(), document.backend(),
                document.createdAt(), document.updatedAt(), document.error());
    }

    @Override
    public void replaceChunks(String tenantId, String docId, List<StoredChunk> chunks) {
        jdbc.update("DELETE FROM knowledge_chunk WHERE tenant_id = ? AND doc_id = ?", tenantId, docId);
        for (StoredChunk chunk : chunks) {
            jdbc.update("""
                    INSERT INTO knowledge_chunk (chunk_id, doc_id, tenant_id, chunk_index, heading, content,
                                                 char_count, vector_backend, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    chunk.chunkId(), chunk.docId(), chunk.tenantId(), chunk.chunkIndex(), chunk.heading(),
                    chunk.content(), chunk.charCount(), chunk.vectorBackend(), chunk.createdAt());
        }
    }

    @Override
    public StoredDocument findDocument(String tenantId, String docId) {
        List<StoredDocument> rows = jdbc.query(
                "SELECT * FROM knowledge_document WHERE tenant_id = ? AND doc_id = ?", documentMapper, tenantId, docId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<StoredDocument> listDocuments(String tenantId, int limit) {
        return jdbc.query("SELECT * FROM knowledge_document WHERE tenant_id = ? ORDER BY updated_at DESC LIMIT ?",
                documentMapper, tenantId, Math.max(1, Math.min(limit, 200)));
    }

    @Override
    public List<StoredChunk> listChunks(String tenantId, String docId) {
        return jdbc.query("SELECT * FROM knowledge_chunk WHERE tenant_id = ? AND doc_id = ? ORDER BY chunk_index",
                chunkMapper, tenantId, docId);
    }

    @Override
    public boolean deleteDocument(String tenantId, String docId) {
        jdbc.update("DELETE FROM knowledge_chunk WHERE tenant_id = ? AND doc_id = ?", tenantId, docId);
        int removed = jdbc.update("DELETE FROM knowledge_document WHERE tenant_id = ? AND doc_id = ?", tenantId, docId);
        if (removed == 0) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND, "document not found: " + docId);
        }
        return true;
    }

    @Override
    public long countDocuments(String tenantId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document WHERE tenant_id = ?",
                Long.class, tenantId);
        return count == null ? 0 : count;
    }

    @Override
    public long countChunks(String tenantId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_chunk WHERE tenant_id = ?",
                Long.class, tenantId);
        return count == null ? 0 : count;
    }

    @Override
    public String backend() { return "pg"; }
}
