package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_voucher_order")
public class VoucherOrder implements Serializable {

    private static final long serialVersionUID = 1L;

    // ========== 订单状态常量 ==========
    // 用常量代替魔法数字，代码可读性更好，也避免写错
    public static final Integer STATUS_UNPAID = 1;      // 未支付（刚下单，等待付款）
    public static final Integer STATUS_PAID = 2;        // 已支付（支付回调确认）
    public static final Integer STATUS_USED = 3;        // 已核销（到店消费了）
    public static final Integer STATUS_CANCELLED = 4;   // 已取消（超时未支付自动关单）
    public static final Integer STATUS_REFUNDING = 5;   // 退款中
    public static final Integer STATUS_REFUNDED = 6;    // 已退款
    // ====================================

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    /**
     * 下单的用户id
     */
    private Long userId;

    /**
     * 购买的代金券id
     */
    private Long voucherId;

    /**
     * 支付方式 1：余额支付；2：支付宝；3：微信
     */
    private Integer payType;

    /**
     * 订单状态，1：未支付；2：已支付；3：已核销；4：已取消；5：退款中；6：已退款
     */
    private Integer status;

    /** Durable completion marker for the post-commit Redis stock release. */
    private Integer redisStockReleased;

    /**
     * 下单时间
     */
    private LocalDateTime createTime;

    /**
     * 支付时间
     */
    private LocalDateTime payTime;

    /**
     * 核销时间
     */
    private LocalDateTime useTime;

    /**
     * 退款时间
     */
    private LocalDateTime refundTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;


}
