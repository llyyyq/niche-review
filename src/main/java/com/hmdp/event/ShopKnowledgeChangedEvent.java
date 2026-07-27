package com.hmdp.event;

public class ShopKnowledgeChangedEvent {

    private final Long shopId;

    public ShopKnowledgeChangedEvent(Long shopId) {
        this.shopId = shopId;
    }

    public Long getShopId() {
        return shopId;
    }
}
