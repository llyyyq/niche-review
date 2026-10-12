package com.hmdp.service.impl;

import com.hmdp.entity.SeckillStockWarmupTask;
import com.hmdp.mapper.SeckillStockWarmupTaskMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SeckillStockWarmupTaskServiceImplTest {

    private final SeckillStockWarmupTaskMapper mapper = mock(SeckillStockWarmupTaskMapper.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final SeckillStockWarmupTaskServiceImpl service = new SeckillStockWarmupTaskServiceImpl();

    SeckillStockWarmupTaskServiceImplTest() {
        ReflectionTestUtils.setField(service, "warmupTaskMapper", mapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void writesStockWithSetIfAbsentAndMarksCompleted() {
        givenDueTask(pendingTask(0));
        when(values.setIfAbsent("seckill:stock:100", "50")).thenReturn(true);

        service.processPendingTasks();

        verify(mapper).markCompleted(1L, 1L);
        verify(values, never()).set(anyString(), anyString());
    }

    @Test
    void existingKeyIsNotOverwrittenAndTaskStillCompletes() {
        givenDueTask(pendingTask(0));
        when(values.setIfAbsent("seckill:stock:100", "50")).thenReturn(false);

        service.processPendingTasks();

        verify(mapper).markCompleted(1L, 1L);
        verify(values, never()).set(anyString(), anyString());
    }

    @Test
    void redisFailureSchedulesRetry() {
        givenDueTask(pendingTask(0));
        when(values.setIfAbsent(anyString(), anyString())).thenThrow(new RuntimeException("redis down"));

        service.processPendingTasks();

        verify(mapper).markPendingForRetry(eq(1L), eq(1L), any(LocalDateTime.class), eq("redis down"));
        verify(mapper, never()).markFailed(anyLong(), anyLong(), anyString());
    }

    @Test
    void exhaustedRetriesMarkTaskFailed() {
        // retryCount 9 means this claim is the tenth and last attempt
        givenDueTask(pendingTask(9));
        when(values.setIfAbsent(anyString(), anyString())).thenThrow(new RuntimeException("redis down"));

        service.processPendingTasks();

        verify(mapper).markFailed(1L, 1L, "redis down");
        verify(mapper, never()).markPendingForRetry(anyLong(), anyLong(), any(LocalDateTime.class), anyString());
    }

    private SeckillStockWarmupTask pendingTask(int retryCount) {
        SeckillStockWarmupTask task = new SeckillStockWarmupTask();
        task.setId(1L);
        task.setVoucherId(100L);
        task.setStock(50);
        task.setRetryCount(retryCount);
        task.setVersion(0L);
        return task;
    }

    private void givenDueTask(SeckillStockWarmupTask task) {
        when(mapper.selectList(any())).thenReturn(Collections.singletonList(task));
        when(mapper.claim(task.getId(), task.getVersion())).thenReturn(1);
    }
}
