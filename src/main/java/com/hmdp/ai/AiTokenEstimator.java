package com.hmdp.ai;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * A provider-independent approximation for monitoring and prompt budgeting.
 * Provider-reported usage can replace this later without changing the persistence model.
 */
@Component
public class AiTokenEstimator {

    public int estimateMessages(List<AiPromptMessage> messages) {
        int total = 0;
        for (AiPromptMessage message : messages) {
            total += estimateText(message.getContent()) + 4;
        }
        return total;
    }

    public int estimateText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int nonAscii = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) {
                nonAscii++;
            }
        }
        int ascii = text.length() - nonAscii;
        return Math.max(1, (int) Math.ceil(nonAscii * 1.5D + ascii / 4.0D));
    }
}
