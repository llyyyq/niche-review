package com.hmdp.mapper;

import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface VoucherOrderMapper extends BaseMapper<VoucherOrder> {

    @Update("UPDATE tb_voucher_order SET redis_stock_released = 1 "
            + "WHERE id = #{orderId} AND status = 4 AND redis_stock_released = 0")
    int markRedisStockReleased(@Param("orderId") Long orderId);

}
