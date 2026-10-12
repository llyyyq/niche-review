package com.hmdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.entity.VoucherOrderDispatchTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface VoucherOrderDispatchTaskMapper extends BaseMapper<VoucherOrderDispatchTask> {

    @Update("UPDATE tb_voucher_order_dispatch_task "
            + "SET status = 1, retry_count = retry_count + 1, processing_at = NOW(), version = version + 1 "
            + "WHERE id = #{id} AND version = #{version} AND status = 0 AND next_retry_at <= NOW()")
    int claim(@Param("id") Long id, @Param("version") Long version);

    @Update("UPDATE tb_voucher_order_dispatch_task "
            + "SET status = 2, processing_at = NULL, last_error = NULL, version = version + 1 "
            + "WHERE id = #{id} AND version = #{version} AND status = 1")
    int markSent(@Param("id") Long id, @Param("version") Long version);

    @Update("UPDATE tb_voucher_order_dispatch_task "
            + "SET status = 0, next_retry_at = #{nextRetryAt}, processing_at = NULL, last_error = #{lastError}, version = version + 1 "
            + "WHERE id = #{id} AND version = #{version} AND status = 1")
    int markPendingForRetry(@Param("id") Long id, @Param("version") Long version,
                            @Param("nextRetryAt") LocalDateTime nextRetryAt, @Param("lastError") String lastError);

    @Update("UPDATE tb_voucher_order_dispatch_task "
            + "SET status = 3, processing_at = NULL, last_error = NULL, version = version + 1 "
            + "WHERE order_id = #{orderId} AND status IN (0, 1, 2)")
    int markCompletedByOrderId(@Param("orderId") Long orderId);

    @Update("UPDATE tb_voucher_order_dispatch_task "
            + "SET status = 4, processing_at = NULL, last_error = #{lastError}, version = version + 1 "
            + "WHERE order_id = #{orderId} AND status IN (0, 1, 2)")
    int markCancelledByOrderId(@Param("orderId") Long orderId, @Param("lastError") String lastError);

    @Update("UPDATE tb_voucher_order_dispatch_task "
            + "SET status = 5, processing_at = NULL, last_error = #{lastError}, version = version + 1 "
            + "WHERE id = #{id} AND version = #{version} AND status = 1")
    int markFailed(@Param("id") Long id, @Param("version") Long version, @Param("lastError") String lastError);

    @Update("UPDATE tb_voucher_order_dispatch_task "
            + "SET status = 0, next_retry_at = NOW(), processing_at = NULL, version = version + 1 "
            + "WHERE status = 1 AND processing_at < #{expiredBefore}")
    int recoverTimedOutProcessing(@Param("expiredBefore") LocalDateTime expiredBefore);
}
