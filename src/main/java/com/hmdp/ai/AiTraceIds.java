package com.hmdp.ai;

import java.security.SecureRandom;

public final class AiTraceIds {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private AiTraceIds() {
    }

    public static String requestId() {
        return randomHex(16);
    }

    public static String traceId() {
        return randomHex(16);
    }

    public static String spanId() {
        return randomHex(8);
    }

    public static String toolCallId() {
        return randomHex(16);
    }

    private static String randomHex(int byteCount) {
        byte[] bytes = new byte[byteCount];
        RANDOM.nextBytes(bytes);
        char[] chars = new char[byteCount * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            chars[i * 2] = HEX[value >>> 4];
            chars[i * 2 + 1] = HEX[value & 0x0f];
        }
        return new String(chars);
    }
}
