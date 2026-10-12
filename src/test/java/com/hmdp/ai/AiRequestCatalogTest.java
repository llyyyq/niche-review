package com.hmdp.ai;

import com.hmdp.entity.Shop;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiRequestCatalogTest {

    private final AtomicInteger loads = new AtomicInteger();
    private final List<Shop> shops = Collections.emptyList();

    @AfterEach
    void closeScope() {
        AiRequestCatalog.close();
    }

    @Test
    void loadsDirectlyOutsideRequestScope() {
        AiRequestCatalog.shops(this::countingLoader);
        AiRequestCatalog.shops(this::countingLoader);

        assertEquals(2, loads.get());
    }

    @Test
    void sharesOneLoadWithinRequestScope() {
        AiRequestCatalog.open();

        List<Shop> first = AiRequestCatalog.shops(this::countingLoader);
        List<Shop> second = AiRequestCatalog.shops(this::countingLoader);

        assertEquals(1, loads.get());
        assertSame(first, second);
    }

    @Test
    void reloadsAfterScopeCloses() {
        AiRequestCatalog.open();
        AiRequestCatalog.shops(this::countingLoader);
        AiRequestCatalog.close();

        AiRequestCatalog.shops(this::countingLoader);

        assertEquals(2, loads.get());
    }

    @Test
    void failedLoadIsRetriedInsideScope() {
        AiRequestCatalog.open();
        AtomicInteger attempts = new AtomicInteger();
        Supplier<List<Shop>> failingOnce = () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("database unavailable");
            }
            return shops;
        };

        assertThrows(IllegalStateException.class, () -> AiRequestCatalog.shops(failingOnce));
        assertSame(shops, AiRequestCatalog.shops(failingOnce));
        assertEquals(2, attempts.get());
    }

    private List<Shop> countingLoader() {
        loads.incrementAndGet();
        return shops;
    }
}
