package com.hmdp.ai;

import java.util.List;

public class AiToolInvocation {

    private final Long conversationId;
    private final Long userId;
    private final String toolName;
    private final String question;
    private final Double x;
    private final Double y;
    private final List<ShopKnowledge> retrievedShops;

    public AiToolInvocation(Long conversationId, Long userId, String toolName, String question,
                            Double x, Double y, List<ShopKnowledge> retrievedShops) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.toolName = toolName;
        this.question = question;
        this.x = x;
        this.y = y;
        this.retrievedShops = retrievedShops;
    }

    public Long getConversationId() { return conversationId; }
    public Long getUserId() { return userId; }
    public String getToolName() { return toolName; }
    public String getQuestion() { return question; }
    public Double getX() { return x; }
    public Double getY() { return y; }
    public List<ShopKnowledge> getRetrievedShops() { return retrievedShops; }
}
