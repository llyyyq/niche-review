package com.hmdp.service;

public interface IAiKnowledgeSyncTaskService {

    void enqueue(Long shopId);

    void processDueTasks();
}
