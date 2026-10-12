package com.hmdp.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * One independent business goal extracted from the user's question.
 * Structured conditions are used by the program; query is used only for semantic retrieval.
 */
public final class AiQueryTask {

    private final String intent;
    private final String query;
    private final List<String> shopNames;
    private final List<String> excludedShopNames;
    private final String category;
    private final String area;
    private final Integer maxAveragePrice;
    private final List<String> semanticPreferences;
    private final List<String> requiredFacts;

    public AiQueryTask(String intent, String query, List<String> shopNames,
                       List<String> excludedShopNames, String category, String area,
                       Integer maxAveragePrice, List<String> semanticPreferences,
                       List<String> requiredFacts) {
        this.intent = intent;
        this.query = query;
        this.shopNames = immutableUnique(shopNames);
        this.excludedShopNames = immutableUnique(excludedShopNames);
        this.category = category;
        this.area = area;
        this.maxAveragePrice = maxAveragePrice;
        this.semanticPreferences = immutableUnique(semanticPreferences);
        this.requiredFacts = immutableUnique(requiredFacts);
    }

    public String getIntent() {
        return intent;
    }

    public String getQuery() {
        return query;
    }

    public List<String> getShopNames() {
        return shopNames;
    }

    public List<String> getExcludedShopNames() {
        return excludedShopNames;
    }

    public String getCategory() {
        return category;
    }

    public String getArea() {
        return area;
    }

    public Integer getMaxAveragePrice() {
        return maxAveragePrice;
    }

    public List<String> getSemanticPreferences() {
        return semanticPreferences;
    }

    public List<String> getRequiredFacts() {
        return requiredFacts;
    }

    public boolean requiresFact(String fact) {
        return fact != null && requiredFacts.contains(fact);
    }

    private List<String> immutableUnique(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                unique.add(value.trim());
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(unique));
    }
}
