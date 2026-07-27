package com.hmdp;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.User;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.service.impl.UserServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.*;

@SpringBootTest
class HmDianPingApplicationTests {
    private static final int JMETER_USER_COUNT = 10_000;
    private static final String JMETER_PHONE_PREFIX = "139900";
    private static final Path JMETER_TOKEN_FILE = Paths.get("tokens.csv");

    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void testSaveShop() {
        shopService.saveShop2Redis(1L, 10L);
    }

    @Resource
    private RedisIdWorker redisIdWorker;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Resource
    private UserServiceImpl userService;

    @Test
    void testIdWorkers() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }


    @Test
    void loadShopData() {
        // 1. 查询店铺信息
        List<Shop> list = shopService.list();
        // 2. 将店铺信息按照typeId分组，一致的分到一组
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3. 批量写入Redis
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()){
            // 3.1 获取类型id
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            // 3.2 获取同类型的店铺信息
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            for (Shop shop : value) {
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                ));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

    @Test
    void testHyperLogLog() {
        String[] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000;
            values[j] = "user_" + i;
            if (j == 999) {
                stringRedisTemplate.opsForHyperLogLog().add("hl1", values);
            }
        }
        long count = stringRedisTemplate.opsForHyperLogLog().size("hl1");
        System.out.println("count = " + count);
    }

    @Test
    void prepareJmeterTokens() throws IOException {
        // Reuse this fixed number range on later runs instead of inserting another 10,000 users.
        Map<String, User> usersByPhone = userService.query()
                .likeRight("phone", JMETER_PHONE_PREFIX)
                .list()
                .stream()
                .collect(Collectors.toMap(User::getPhone, user -> user, (first, ignored) -> first));

        List<User> usersToCreate = new ArrayList<>();
        for (int i = 0; i < JMETER_USER_COUNT; i++) {
            String phone = String.format("%s%05d", JMETER_PHONE_PREFIX, i);
            if (usersByPhone.containsKey(phone)) {
                continue;
            }

            User user = new User();
            user.setPhone(phone);
            user.setNickName("jmeter_user_" + i);
            user.setIcon("");
            usersToCreate.add(user);
            usersByPhone.put(phone, user);
        }
        if (!usersToCreate.isEmpty()) {
            userService.saveBatch(usersToCreate, 500);
        }

        try (BufferedWriter writer = Files.newBufferedWriter(
                JMETER_TOKEN_FILE,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            writer.write("token");
            writer.newLine();

            for (int i = 0; i < JMETER_USER_COUNT; i++) {
                String phone = String.format("%s%05d", JMETER_PHONE_PREFIX, i);
                User user = usersByPhone.get(phone);
                if (user == null || user.getId() == null) {
                    throw new IllegalStateException("JMeter test user was not created, phone=" + phone);
                }
                String token = UUID.randomUUID().toString(true);

                UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
                Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                        CopyOptions.create()
                                .setIgnoreNullValue(true)
                                .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));

                String tokenKey = LOGIN_USER_KEY + token;
                stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
                stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

                writer.write(token);
                writer.newLine();
            }
        }

        System.out.printf("Generated %d JMeter tokens at %s. Tokens expire after %d minutes.%n",
                JMETER_USER_COUNT,
                JMETER_TOKEN_FILE.toAbsolutePath(),
                LOGIN_USER_TTL);
    }


}
