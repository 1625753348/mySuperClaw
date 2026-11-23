package com.example.enterpriseagent.runtime.execution;

import com.example.enterpriseagent.runtime.RunModel.Artifact;
import java.util.UUID;

/** 根据已校验归属的产物记录恢复正文。 */
@FunctionalInterface
public interface ArtifactBodyReader {
    String restore(String tenant, UUID run, Artifact artifact);

    final class Unavailable extends RuntimeException {
        public Unavailable() {
            super("运行产物暂时无法读取");
        }
    }
}
