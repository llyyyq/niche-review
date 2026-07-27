package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.AiKnowledgeSyncTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface AiKnowledgeSyncTaskMapper extends BaseMapper<AiKnowledgeSyncTask> {

    @Update("INSERT INTO tb_ai_knowledge_sync_task "
            + "(shop_id, status, retry_count, next_retry_at, version) "
            + "VALUES (#{shopId}, 0, 0, NOW(), 1) "
            + "ON DUPLICATE KEY UPDATE status = 0, retry_count = 0, next_retry_at = NOW(), "
            + "processing_at = NULL, last_error = NULL, version = version + 1")
    int upsertPending(@Param("shopId") Long shopId);

    @Update("UPDATE tb_ai_knowledge_sync_task "
            + "SET status = 1, retry_count = retry_count + 1, processing_at = NOW() "
            + "WHERE id = #{id} AND version = #{version} AND status = 0 AND next_retry_at <= NOW()")
    int claim(@Param("id") Long id, @Param("version") Long version);

    @Update("UPDATE tb_ai_knowledge_sync_task "
            + "SET status = 2, next_retry_at = NULL, processing_at = NULL, last_error = NULL "
            + "WHERE id = #{id} AND version = #{version} AND status = 1")
    int markSucceeded(@Param("id") Long id, @Param("version") Long version);

    @Update("UPDATE tb_ai_knowledge_sync_task "
            + "SET status = #{status}, next_retry_at = #{nextRetryAt}, processing_at = NULL, last_error = #{lastError} "
            + "WHERE id = #{id} AND version = #{version} AND status = 1")
    int markFailedOrPending(@Param("id") Long id,
                            @Param("version") Long version,
                            @Param("status") Integer status,
                            @Param("nextRetryAt") LocalDateTime nextRetryAt,
                            @Param("lastError") String lastError);

    @Update("UPDATE tb_ai_knowledge_sync_task "
            + "SET status = 0, next_retry_at = NOW(), processing_at = NULL "
            + "WHERE status = 1 AND processing_at < #{expiredBefore}")
    int recoverTimedOutProcessing(@Param("expiredBefore") LocalDateTime expiredBefore);
}
