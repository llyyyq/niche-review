package com.hmdp.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Program-derived constraints for one task's read-only tool calls.
 * Tool implementations must not widen this scope from semantic candidates.
 */
public final class AiToolQueryScope {

    private final List<Long> shopIds;
    private final List<Long> excludedShopIds;
    private final List<Long> typeIds;
    private final List<String> areas;
    private final Integer maxAveragePrice;

    public AiToolQueryScope(List<Long> shopIds, List<Long> excludedShopIds,
                            List<Long> typeIds, List<String> areas,
                            Integer maxAveragePrice) {
        this.shopIds = unique(shopIds);
        this.excludedShopIds = unique(excludedShopIds);
        this.typeIds = unique(typeIds);
        this.areas = unique(areas);
        this.maxAveragePrice = maxAveragePrice;
    }

    public List<Long> getShopIds() {
        return shopIds;
    }

    public List<Long> getExcludedShopIds() {
        return excludedShopIds;
    }

    public List<Long> getTypeIds() {
        return typeIds;
    }

    public List<String> getAreas() {
        return areas;
    }

    public Integer getMaxAveragePrice() {
        return maxAveragePrice;
    }

    private <T> List<T> unique(List<T> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<T> result = new LinkedHashSet<>();
        for (T value : values) {
            if (value != null) {
                result.add(value);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(result));
    }
}
