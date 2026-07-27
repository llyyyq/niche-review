package com.hmdp.service;

import java.time.LocalDateTime;

public interface IOrderTimeoutTaskService {

    void createPendingTask(Long orderId, LocalDateTime dueAt);

    void cancelPendingTask(Long orderId);

    void processDueTasks();
}
