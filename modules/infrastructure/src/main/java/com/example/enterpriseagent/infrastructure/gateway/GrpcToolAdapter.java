package com.example.enterpriseagent.infrastructure.gateway;

import com.example.enterpriseagent.toolgateway.adapter.ToolAdapter;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import io.grpc.CallOptions;
import io.grpc.ClientInterceptors;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 使用受控 .proto 描述符把工具 JSON 映射成 unary gRPC 请求。 */
public final class GrpcToolAdapter implements ToolAdapter {
    private final Map<String, GrpcToolBinding> bindings;
    private static final Metadata.Key<String> TRACE = Metadata.Key.of("x-trace-id", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> IDEMPOTENCY =
            Metadata.Key.of("idempotency-key", Metadata.ASCII_STRING_MARSHALLER);

    public GrpcToolAdapter(List<GrpcToolBinding> bindings) {
        this.bindings = bindings.stream().collect(Collectors.toUnmodifiableMap(GrpcToolBinding::name, Function.identity()));
    }

    @Override public Protocol protocol() { return Protocol.GRPC; }

    @Override public boolean configured(String targetRef) { return bindings.containsKey(targetRef); }

    @Override public Set<String> targetRefs() { return bindings.keySet(); }

    @Override
    public Outcome invoke(Call call) {
        GrpcToolBinding binding = bindings.get(call.targetRef());
        if (binding == null) {
            return new Outcome("FAILED", null, "GRPC_METHOD_NOT_CONFIGURED");
        }
        var descriptor = binding.method();
        var method = MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(descriptor.getService().getFullName(), descriptor.getName()))
                .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(descriptor.getInputType())))
                .setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(descriptor.getOutputType())))
                .build();
        try {
            var request = DynamicMessage.newBuilder(descriptor.getInputType());
            JsonFormat.parser().merge(call.arguments(), request);
            var headers = new Metadata();
            headers.put(TRACE, call.traceId());
            headers.put(IDEMPOTENCY, call.operationKey());
            var channel = ClientInterceptors.intercept(binding.channel(), MetadataUtils.newAttachHeadersInterceptor(headers));
            var output = ClientCalls.blockingUnaryCall(channel, method, CallOptions.DEFAULT
                    .withDeadlineAfter(call.timeoutMs(), TimeUnit.MILLISECONDS).withMaxInboundMessageSize(262_144), request.build());
            return new Outcome("SUCCEEDED", JsonFormat.printer().omittingInsignificantWhitespace().print(output), null);
        } catch (InvalidProtocolBufferException ex) {
            return new Outcome("FAILED", null, "GRPC_PROTO_JSON_INVALID");
        } catch (StatusRuntimeException ex) {
            var status = ex.getStatus().getCode();
            return switch (status) {
                case INVALID_ARGUMENT, UNAUTHENTICATED, PERMISSION_DENIED, NOT_FOUND, UNIMPLEMENTED ->
                        new Outcome("FAILED", null, "GRPC_" + status.name());
                default -> new Outcome("UNKNOWN", null, "GRPC_" + status.name());
            };
        }
    }
}
