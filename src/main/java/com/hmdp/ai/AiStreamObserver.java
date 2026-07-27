package com.hmdp.ai;

@FunctionalInterface
public interface AiStreamObserver {

    void onDelta(String content) throws Exception;
}
