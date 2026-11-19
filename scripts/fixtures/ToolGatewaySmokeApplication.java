import com.example.enterpriseagent.EnterpriseAgentApplication;
import com.example.enterpriseagent.infrastructure.gateway.GrpcToolBinding;
import com.example.enterpriseagent.infrastructure.gateway.DatabaseToolBinding;
import com.example.enterpriseagent.toolgateway.adapter.LocalTool;
import com.example.enterpriseagent.toolgateway.adapter.ToolAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.StructProto;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ServerCalls;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Set;
import javax.sql.DataSource;

/** 联调入口。测试用 Java 工具和 gRPC 服务不打进业务 JAR。 */
@Configuration(proxyBeanMethods = false)
public class ToolGatewaySmokeApplication {
    public static void main(String[] args) {
        new SpringApplicationBuilder(EnterpriseAgentApplication.class, ToolGatewaySmokeApplication.class).run(args);
    }

    @Bean
    LocalTool smokeLocalTool(ObjectMapper json) {
        return new LocalTool() {
            @Override public String name() { return "local-lookup"; }

            @Override
            public JsonNode run(JsonNode arguments, ToolAdapter.Call call) {
                return json.createObjectNode().put("orderId", arguments.path("orderId").asText())
                        .put("status", "LOCAL_READY");
            }
        };
    }

    @Bean
    Descriptors.MethodDescriptor smokeRpcDescriptor() throws Descriptors.DescriptorValidationException {
        var proto = DescriptorProtos.FileDescriptorProto.newBuilder().setName("gateway_smoke.proto")
                .setPackage("smoke").addDependency(StructProto.getDescriptor().getName())
                .addService(DescriptorProtos.ServiceDescriptorProto.newBuilder().setName("Orders")
                        .addMethod(DescriptorProtos.MethodDescriptorProto.newBuilder().setName("Lookup")
                                .setInputType(".google.protobuf.Struct").setOutputType(".google.protobuf.Struct")))
                .build();
        return Descriptors.FileDescriptor.buildFrom(proto, new Descriptors.FileDescriptor[]{StructProto.getDescriptor()})
                .findServiceByName("Orders").findMethodByName("Lookup");
    }

    @Bean
    DatabaseToolBinding smokeDatabaseQuery(DataSource source,
            @Value("${enterprise.gateway.smoke-tenant}") String tenant) {
        return new DatabaseToolBinding("database-lookup", source,
                "SELECT :tenantId AS tenant, :orderId AS order_id", Set.of(tenant), 5);
    }

    @Bean(destroyMethod = "shutdownNow")
    Server smokeRpcServer(Descriptors.MethodDescriptor descriptor) throws IOException {
        var wireMethod = MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName(MethodDescriptor.generateFullMethodName("smoke.Orders", "Lookup"))
                .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(descriptor.getInputType())))
                .setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(descriptor.getOutputType())))
                .build();
        var service = ServerServiceDefinition.builder("smoke.Orders")
                .addMethod(wireMethod, ServerCalls.asyncUnaryCall((request, response) -> {
                    response.onNext(request);
                    response.onCompleted();
                })).build();
        return NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                .addService(service).build().start();
    }

    @Bean(destroyMethod = "shutdownNow")
    ManagedChannel smokeRpcChannel(Server server) {
        return ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
    }

    @Bean
    GrpcToolBinding smokeGrpcTool(ManagedChannel channel, Descriptors.MethodDescriptor descriptor) {
        return new GrpcToolBinding("grpc-lookup", channel, descriptor);
    }
}
