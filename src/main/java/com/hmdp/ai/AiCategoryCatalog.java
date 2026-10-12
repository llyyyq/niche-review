package com.hmdp.ai;

import com.hmdp.entity.ShopType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The runtime vocabulary of business categories.
 *
 * The database owns the canonical names. This class deliberately does not
 * guess aliases; natural-language normalization belongs to the query model.
 */
public final class AiCategoryCatalog {

    private final List<String> names;
    private final Map<String, Long> idsByNormalizedName;

    private AiCategoryCatalog(List<String> names, Map<String, Long> idsByNormalizedName) {
        this.names = Collections.unmodifiableList(new ArrayList<>(names));
        this.idsByNormalizedName = Collections.unmodifiableMap(
                new LinkedHashMap<>(idsByNormalizedName));
    }

    public static AiCategoryCatalog from(List<ShopType> types) {
        List<String> names = new ArrayList<>();
        Map<String, Long> ids = new LinkedHashMap<>();
        if (types != null) {
            for (ShopType type : types) {
                if (type == null || type.getId() == null || isBlank(type.getName())) {
                    continue;
                }
                String name = type.getName().trim();
                String normalized = normalize(name);
                if (!ids.containsKey(normalized)) {
                    names.add(name);
                    ids.put(normalized, type.getId());
                }
            }
        }
        return new AiCategoryCatalog(names, ids);
    }

    public static AiCategoryCatalog empty() {
        return new AiCategoryCatalog(Collections.<String>emptyList(),
                Collections.<String, Long>emptyMap());
    }

    public boolean isEmpty() {
        return names.isEmpty();
    }

    public List<String> getNames() {
        return names;
    }

    public boolean contains(String name) {
        return !isBlank(name) && idsByNormalizedName.containsKey(normalize(name));
    }

    public List<Long> resolve(String name) {
        if (!contains(name)) {
            return Collections.emptyList();
        }
        return Collections.singletonList(idsByNormalizedName.get(normalize(name)));
    }

    public String describe() {
        return String.join("、", names);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", "").toLowerCase();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
