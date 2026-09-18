package com.agent.body.chunk;

import com.agent.body.common.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R3-02 文档分块验收：分块无遗漏、块大小受控、标题归属保留、边界可配。
 */
class ChunkProcessorTest {

    private final ChunkProcessor processor = new ChunkProcessor(800, 200, 50);

    @Test
    void chunkingDoesNotLoseContent() {
        String content = """
                # 躯体层设计
                知识以分块方式入库，语义检索按块召回。

                ## 存储分层
                热层用 Redis 缓存高频问题，温层用 Qdrant 向量库，冷层用 PostgreSQL 保存元数据真相。

                ## 检索链路
                查询先查缓存，未命中则向量化后检索 TOP50，再经重排取 TOP5。
                """;
        List<ChunkProcessor.Chunk> chunks = processor.chunk(content);
        assertFalse(chunks.isEmpty());
        String joined = chunks.stream().map(ChunkProcessor.Chunk::content).collect(Collectors.joining());
        for (String keyword : List.of("知识以分块方式入库", "热层用 Redis", "查询先查缓存", "再经重排取 TOP5")) {
            assertTrue(joined.contains(keyword), "分块遗漏关键词：" + keyword);
        }
    }

    @Test
    void eachChunkKeepsItsHeading() {
        String content = """
                # 一级标题
                正文甲。

                ## 二级标题
                正文乙。
                """;
        List<ChunkProcessor.Chunk> chunks = processor.chunk(content);
        assertTrue(chunks.stream().anyMatch(c -> "一级标题".equals(c.heading()) && c.content().startsWith("一级标题")));
        assertTrue(chunks.stream().anyMatch(c -> "二级标题".equals(c.heading()) && c.content().startsWith("二级标题")));
    }

    @Test
    void chunkSizeStaysWithinBudget() {
        StringBuilder builder = new StringBuilder("# 长文档\n");
        for (int i = 0; i < 60; i++) {
            builder.append("这是第 ").append(i).append(" 段说明文字，用于验证分块大小受控。\n\n");
        }
        List<ChunkProcessor.Chunk> chunks = processor.chunk(builder.toString());
        for (ChunkProcessor.Chunk chunk : chunks) {
            // 预算 = maxChars + 标题 + 重叠尾巴
            assertTrue(chunk.content().length() <= 800 + 64 + 50,
                    "块超预算：" + chunk.content().length());
        }
    }

    @Test
    void oversizedParagraphIsSplitBySentenceBoundary() {
        StringBuilder builder = new StringBuilder("# 超长段\n");
        for (int i = 0; i < 200; i++) {
            builder.append("第").append(i).append("句内容；");
        }
        List<ChunkProcessor.Chunk> chunks = processor.chunk(builder.toString());
        assertTrue(chunks.size() > 1, "超长段落应被切分为多块");
    }

    @Test
    void blankContentIsRejected() {
        assertThrows(BizException.class, () -> processor.chunk("   "));
        assertThrows(BizException.class, () -> processor.chunk(null));
    }

    @Test
    void invalidParametersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ChunkProcessor(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ChunkProcessor(100, 200, 10));
        // 重叠必须小于块大小，否则块会自我膨胀
        assertThrows(IllegalArgumentException.class, () -> new ChunkProcessor(100, 10, 100));
    }

    @Test
    void chunkIndexIsSequentialFromZero() {
        String content = "# 标题\n甲段落。\n\n乙段落。\n\n丙段落。";
        List<ChunkProcessor.Chunk> chunks = processor.chunk(content);
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).index());
        }
    }
}
