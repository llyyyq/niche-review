package com.hmdp.service;

import com.hmdp.entity.VoucherOrder;

public interface IVoucherOrderDispatchTaskService {

    void createPendingTask(VoucherOrder order);

    void markOrderCompleted(Long orderId);

    void markOrderCancelled(Long orderId, String reason);

    void processPendingTasks();
}
