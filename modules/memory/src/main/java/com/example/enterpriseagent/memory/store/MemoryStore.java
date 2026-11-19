package com.example.enterpriseagent.memory.store;

/** 供记忆服务调用的数据端口。存储适配器负责实际的读写和一致性检查。 */
public interface MemoryStore<K, R, W> {

    R load(K key);

    void save(W value);
}
