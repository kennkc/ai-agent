package com.agent.sense.staging;

import com.agent.sense.model.CollectedData;

import java.util.List;
import java.util.Map;

/**
 * 隔离暂存区抽象（R2-10）
 * 采集数据先进暂存区（质检查验后 ACCEPTED / REJECTED），Phase 3 才消费入库；
 * 支持按批次回滚。
 */
public interface StagingBackend {

    /** 后端名称：minio / local */
    String name();

    boolean available();

    /** 写入一条采集数据，返回对象引用 */
    String put(CollectedData data);

    /** 按批次号取回 */
    CollectedData get(String batchId, String tenantId);

    /** 列出暂存对象（按时间倒序） */
    List<CollectedData> list(String tenantId, String status, int limit);

    /** 批次回滚：删除该批次全部对象 */
    boolean rollback(String batchId, String tenantId);

    /** 暂存区统计 */
    Map<String, Object> stats();
}
