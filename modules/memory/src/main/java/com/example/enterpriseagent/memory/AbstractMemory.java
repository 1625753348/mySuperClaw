package com.example.enterpriseagent.memory;

import java.util.Objects;

/** 定义统一的记忆入口。子类决定读写契约及生命周期。 */
public abstract class AbstractMemory<K, R, W> {

    public final R read(K key) {
        return load(Objects.requireNonNull(key, "记忆读取键不能为空"));
    }

    public final void write(W value) {
        save(Objects.requireNonNull(value, "记忆写入内容不能为空"));
    }

    protected abstract R load(K key);

    protected abstract void save(W value);
}
