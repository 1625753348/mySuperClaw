package com.example.enterpriseagent.infrastructure.gateway;

import com.google.protobuf.Descriptors;
import io.grpc.Channel;

/** 部署侧 Bean 显式提供目标 Channel 与 .proto 方法描述符。模型只看到工具契约。 */
public record GrpcToolBinding(String name, Channel channel, Descriptors.MethodDescriptor method) {
    public GrpcToolBinding {
        if (name == null || name.isBlank() || channel == null || method == null
                || method.isClientStreaming() || method.isServerStreaming()) {
            throw new IllegalArgumentException("gRPC 工具必须显式绑定可用的 unary 方法");
        }
    }
}
