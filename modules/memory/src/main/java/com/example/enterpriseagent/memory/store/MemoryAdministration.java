package com.example.enterpriseagent.memory.store;

import java.util.List;
import java.util.UUID;

/** 写入主体由接入层认证，本文档与档案字段由调用端提供。 */
public interface MemoryAdministration {
    void profile(String tenant, String user, String key, String value, long revision);
    void publish(String tenant, UUID document, int version, String sourceKey, List<String> chunks);

    final class Failure extends RuntimeException {
        private final int status;
        public Failure(int status, String message) {
            super(message);
            this.status = status;
        }
        public int status() { return status; }
    }
}
