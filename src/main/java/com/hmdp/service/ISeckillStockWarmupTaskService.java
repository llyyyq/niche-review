package com.hmdp.service;

public interface ISeckillStockWarmupTaskService {

    void createPendingTask(Long voucherId, Integer stock);

    void processPendingTasks();
}
