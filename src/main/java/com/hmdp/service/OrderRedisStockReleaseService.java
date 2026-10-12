package com.hmdp.service;

public interface OrderRedisStockReleaseService {

    void releaseAfterCommit(Long orderId, Long voucherId);

    void reconcilePendingReleases();
}
