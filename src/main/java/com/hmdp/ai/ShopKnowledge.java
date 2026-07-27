package com.hmdp.ai;

import java.util.Map;

public class ShopKnowledge {

    private final Long shopId;
    private final String content;
    private final Double score;
    private final Map<String, Object> payload;

    public ShopKnowledge(Long shopId, String content, Double score, Map<String, Object> payload) {
        this.shopId = shopId;
        this.content = content;
        this.score = score;
        this.payload = payload;
    }

    public Long getShopId() {
        return shopId;
    }

    public String getContent() {
        return content;
    }

    public Double getScore() {
        return score;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }
}
