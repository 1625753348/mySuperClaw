package com.example.enterpriseagent.memory.store;

import java.util.List;

/** 相似度索引返回引用及分数。读取具体内容前须回权威 Store 复核。 */
public interface MemoryVectorIndex {

    void upsert(String tenantId, Kind kind, String sourceKey, List<Float> vector);

    List<Hit> search(String tenantId, Kind kind, List<Float> vector, int limit);

    enum Kind { EPISODE, SEMANTIC }

    record Hit(String sourceKey, float score) {}
}
