package com.hmdp.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Query task after program-side entity resolution. Model text is never treated as an ID.
 */
public final class AiResolvedQueryTask {

    private final AiQueryTask source;
    private final List<Long> shopIds;
    private final List<Long> excludedShopIds;
    private final List<Long> typeIds;
    private final List<String> areas;

    public AiResolvedQueryTask(AiQueryTask source, List<Long> shopIds,
                               List<Long> excludedShopIds, List<Long> typeIds,
                               List<String> areas) {
        this.source = source;
        this.shopIds = immutableUnique(shopIds);
        this.excludedShopIds = immutableUnique(excludedShopIds);
        this.typeIds = immutableUnique(typeIds);
        this.areas = immutableUnique(areas);
    }

    public AiQueryTask getSource() {
        return source;
    }

    public String getSemanticQuery() {
        return source == null ? "" : source.getQuery();
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
        return source == null ? null : source.getMaxAveragePrice();
    }

    public List<String> getRequiredFacts() {
        return source == null ? Collections.<String>emptyList() : source.getRequiredFacts();
    }

    private <T> List<T> immutableUnique(List<T> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<T> unique = new LinkedHashSet<>();
        for (T value : values) {
            if (value != null) {
                unique.add(value);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(unique));
    }
}
