package com.hmdp.ai;

import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;

import java.util.List;
import java.util.function.Supplier;

/**
 * Shop and shop-type tables read at most once per chat request.
 *
 * The scope is thread-bound, like {@code UserHolder}. Outside an open scope every call loads
 * directly, so offline jobs such as knowledge rebuild keep their current behavior. A failed load
 * is not cached, so a later call in the same request retries it.
 */
public final class AiRequestCatalog {

    private static final ThreadLocal<AiRequestCatalog> CURRENT = new ThreadLocal<>();

    private List<Shop> shops;
    private List<ShopType> types;

    private AiRequestCatalog() {
    }

    /** Starts a request scope on the current thread. Must be paired with {@link #close()}. */
    public static void open() {
        CURRENT.set(new AiRequestCatalog());
    }

    public static void close() {
        CURRENT.remove();
    }

    public static List<Shop> shops(Supplier<List<Shop>> loader) {
        AiRequestCatalog catalog = CURRENT.get();
        if (catalog == null) {
            return loader.get();
        }
        if (catalog.shops == null) {
            catalog.shops = loader.get();
        }
        return catalog.shops;
    }

    public static List<ShopType> types(Supplier<List<ShopType>> loader) {
        AiRequestCatalog catalog = CURRENT.get();
        if (catalog == null) {
            return loader.get();
        }
        if (catalog.types == null) {
            catalog.types = loader.get();
        }
        return catalog.types;
    }
}
