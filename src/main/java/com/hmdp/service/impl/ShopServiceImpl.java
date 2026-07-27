package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.event.ShopKnowledgeChangedEvent;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.SystemConstants;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShopServiceImpl.class);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;
    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    private RBloomFilter<String> shopBloomFilter;

    @PostConstruct
    public void initShopBloomFilter() {
        shopBloomFilter = redissonClient.getBloomFilter(SHOP_BLOOM_FILTER_KEY);
        shopBloomFilter.tryInit(100000L, 0.01);
        list().forEach(shop -> shopBloomFilter.add(shop.getId().toString()));
    }
    @Override
    public Result queryById(Long id) {
        if (!shopBloomFilter.contains(id.toString())) {
            return Result.fail("店铺不存在");
        }
        // 缓存穿透解决
        // Shop shop = queryWithPassThrough(id);

        // 互斥锁解决缓存击穿解决
        // 同时也解决了缓存穿透：数据库中不存在的数据，也缓存一个空值，防止反复查数据库
        Shop shop = queryWithMutex(id);

        // Shop shop = queryWithPassThrough(id);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result saveShop(Shop shop) {
        boolean success = save(shop);
        if (!success) {
            return Result.fail("新增店铺失败");
        }
        shopBloomFilter.add(shop.getId().toString());
        publishKnowledgeChanged(shop.getId());
        return Result.ok(shop.getId());
    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // 逻辑过期解决缓存击穿解决
    public Shop queryWithLogicExpire(Long id) {
        // 1. 从redis中查询
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 如果未命中，返回
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }
        // 3. 命中，将json反序列化为shop对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //4. 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //4.1 未过期，直接返回信息
            return shop;
        }
        //4.2 过期， 进行缓存重建
        // 5 缓存重建
        CACHE_REBUILD_EXECUTOR.submit(() -> rebuildLogicalExpireCache(id));
        // 5.4 返回过期的商铺信息
        return shop;

    }
    /*
    * 互斥锁解决缓存击穿问题
    * 热点key失效问题
    * 同时也解决了缓存穿透：数据库中不存在的数据，也缓存一个空值，防止反复查数据库
    * */
    public Shop queryWithMutex(Long id) {
        // 1. 从redis中查询
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 如果存在，返回
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 3. 判断shopJson是否为空,存的不是null而是""空，说明redis中对这个请求进行了空值的保存，则返回错误
        if (shopJson != null){
            return null;
        }
        // 4. 实现缓存重建

        // 4.1 获取互斥锁
        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "shop:" + id);
        Shop shop = null;
        boolean isLock = false;
        try {
             isLock = lock.tryLock(LOCK_SHOP_TTL);
            // 4.2 判断锁是否获取成功锁
            if (!isLock) {
                // 4.3 获取锁失败,别的线程已经获取到了休眠等待
                Thread.sleep(50);
                // 获取锁失败，重试
                return queryWithMutex(id);
            }

            // 获取锁成功后，再查一次 Redis
            shopJson = stringRedisTemplate.opsForValue().get(key);

            if (StrUtil.isNotBlank(shopJson)) {
                return JSONUtil.toBean(shopJson, Shop.class);
            }

            if (shopJson != null) {
                return null;
            }

            // Redis 仍然没有，才查数据库
            shop = getById(id);
            // 5. 如果数据库也不存在，返回错误
            if (shop == null) {
                // 将空值返回redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 6. 存在，写入redis
            cacheShopWithRandomTtl(key, shop);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 7. 释放锁
            if (isLock) {
                lock.unLock();
            }
        }
        // 8. 返回
        return shop;
    }

    public void saveShop2Redis(Long id, Long expireSeconds) {
        // 1. 查询店铺信息
        Shop shop = getById(id);
        // 2. 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3. 写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    /*
    * 缓存穿透解决
    * 同一个id请求，会进行缓存穿透，导致一直访问数据库，解决方法：使用缓存空值，设置过期时间，防止缓存穿透
    * */
    public  Shop queryWithPassThrough(Long id) {
        // 1. 从redis中查询
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2. 如果存在，返回
        if (StrUtil.isNotBlank(shopJson)) {
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 3. 判断shopJson是否为空,存的不是null而是""空，说明redis中对这个请求进行了空值的保存，则返回错误
        if (shopJson != null){
            return null;
        }
        // 3. 不存在，根据id查询数据库
        Shop shop = getById(id);
        // 4. 如果数据库也不存在，返回错误
        if (shop == null) {
            // 将空值返回redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 5. 存在，写入redis
        cacheShopWithRandomTtl(key, shop);
        // 6. 返回
        return shop;
    }
    private void cacheShopWithRandomTtl(String key, Shop shop) {
        long ttl = CACHE_SHOP_TTL + ThreadLocalRandom.current().nextLong(1, CACHE_SHOP_TTL_RANDOM_MAX + 1);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), ttl, TimeUnit.MINUTES);
    }

    private void rebuildLogicalExpireCache(Long id) {
        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "shop:" + id);
        boolean isLock = false;
        try {
            isLock = lock.tryLock(LOCK_SHOP_TTL);
            if (!isLock) {
                return;
            }
            saveShop2Redis(id, 20L);
        } catch (Exception e) {
            LOGGER.error("Failed to rebuild logical-expire shop cache, shopId={}", id, e);
        } finally {
            if (isLock) {
                lock.unLock();
            }
        }
    }

    /*
    * 用于更新数据库的商铺信息
    * 同时删除redis中的数据
    * */
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1. 更新数据库
        boolean updated = updateById(shop);
        if (!updated) {
            return Result.fail("店铺不存在或更新失败");
        }
        shopBloomFilter.add(id.toString());
        // 2. Delete the cache only after the new database value is committed.
        evictShopCacheAfterCommit(id);
        publishKnowledgeChanged(shop.getId());
        return Result.ok();
    }

    private void evictShopCacheAfterCommit(Long shopId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            stringRedisTemplate.delete(CACHE_SHOP_KEY + shopId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronizationAdapter() {
            @Override
            public void afterCommit() {
                stringRedisTemplate.delete(CACHE_SHOP_KEY + shopId);
            }
        });
    }

    private void publishKnowledgeChanged(Long shopId) {
        if (shopId != null) {
            applicationEventPublisher.publishEvent(new ShopKnowledgeChangedEvent(shopId));
        }
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1. 判断是否需要根据坐标查询
        if (x == null || y == null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId).page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }
        // 根据类型分页查询
        // 2. 计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        // 3. 查询redis，按照距离排序，分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().search(
                key,
                GeoReference.fromCoordinate(x, y),
                new Distance(50000),
                RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().includeCoordinates().limit(end)
        );
        // 4. 解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        // 4.1. 截取从from到end的部分
        list.stream().skip(from).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        if (ids.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        String idsStr = StrUtil.join(",", ids);
        // 5. 根据id查询shop
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idsStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }

        return Result.ok(shops);
    }
}
