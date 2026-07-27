package com.hmdp.ai;

import java.util.List;

public interface EmbeddingModelClient {

    List<List<Float>> embed(List<String> texts) throws Exception;
}
