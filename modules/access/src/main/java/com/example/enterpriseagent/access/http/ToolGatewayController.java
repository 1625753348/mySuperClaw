package com.example.enterpriseagent.access.http;

import com.example.enterpriseagent.toolgateway.GatewayCaller;
import com.example.enterpriseagent.toolgateway.GatewayException;
import com.example.enterpriseagent.toolgateway.execution.ToolExecutionService;
import com.example.enterpriseagent.toolgateway.registry.GatewayRepository;
import com.example.enterpriseagent.toolgateway.registry.ToolRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** 入口负责从认证结果绑定调用上下文，业务校验由网关完成。 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Profile("storage & gateway")
public final class ToolGatewayController {

    private final ToolRegistry registry;
    private final ToolExecutionService execution;

    public ToolGatewayController(ToolRegistry registry, ToolExecutionService execution) {
        this.registry = registry;
        this.execution = execution;
    }

    @PostMapping("/v1/admin/tools")
    @ResponseStatus(HttpStatus.CREATED)
    public GatewayRepository.Tool register(@AuthenticationPrincipal Jwt jwt, @RequestBody ToolRegistry.Register input) {
        return registry.register(caller(jwt), input);
    }

    @PostMapping("/v1/admin/tools/{name}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public ToolRegistry.Contract version(@AuthenticationPrincipal Jwt jwt, @PathVariable String name,
                                         @RequestBody ToolRegistry.Draft draft) {
        return registry.addVersion(caller(jwt), name, draft);
    }

    @PostMapping("/v1/admin/tools/{name}/publish")
    public GatewayRepository.Tool publish(@AuthenticationPrincipal Jwt jwt, @PathVariable String name,
                                          @RequestBody Publish change) {
        return registry.publish(caller(jwt), name, change.version(), revision(change.expectedRevision()));
    }

    @PostMapping("/v1/admin/tools/{name}/disable")
    public GatewayRepository.Tool disable(@AuthenticationPrincipal Jwt jwt, @PathVariable String name,
                                          @RequestBody Disable change) {
        return registry.disable(caller(jwt), name, revision(change.expectedRevision()));
    }

    @PutMapping("/v1/admin/tools/{name}/permissions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void permission(@AuthenticationPrincipal Jwt jwt, @PathVariable String name,
                           @RequestBody ToolRegistry.Grant grant) {
        registry.setPermission(caller(jwt), name, grant);
    }

    @PostMapping("/v1/tools/search")
    public List<GatewayRepository.SearchHit> search(@AuthenticationPrincipal Jwt jwt, @RequestBody Search query) {
        return registry.search(caller(jwt), query.query(), query.limit() == null ? 10 : query.limit());
    }

    @GetMapping("/v1/tools/{name}/schema")
    public ToolRegistry.Contract schema(@AuthenticationPrincipal Jwt jwt, @PathVariable String name,
                                        @RequestParam(required = false) String version) {
        return registry.schema(caller(jwt), name, version);
    }

    @PostMapping("/v1/tools/executions")
    public ResponseEntity<ToolExecutionService.Result> execute(@AuthenticationPrincipal Jwt jwt,
                                                                  @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                                  @RequestBody ToolExecutionService.Request input) {
        var result = execution.execute(caller(jwt), input, key);
        return switch (result.status()) {
            case "RUNNING", "UNKNOWN" -> ResponseEntity.accepted().body(result);
            case "FAILED" -> ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(result);
            default -> ResponseEntity.ok(result);
        };
    }

    @GetMapping("/v1/tools/executions/{executionId}")
    public ToolExecutionService.Result status(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID executionId) {
        return execution.get(caller(jwt), executionId);
    }

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ErrorResponse> error(GatewayException error) {
        var status = switch (error.code()) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case FORBIDDEN, POLICY_REQUIRED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(new ErrorResponse(error.code().name(), error.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse invalidBody() {
        return new ErrorResponse("INVALID", "请求正文不是有效的工具请求 JSON");
    }

    private static GatewayCaller caller(Jwt jwt) {
        if (jwt == null) {
            throw new GatewayException(GatewayException.Code.FORBIDDEN, "缺少认证上下文");
        }
        String agent = jwt.getClaimAsString("agent_id");
        return new GatewayCaller(jwt.getClaimAsString("tenant_id"), jwt.getSubject(),
                agent == null ? jwt.getSubject() : agent, jwt.getClaimAsStringList("roles"));
    }

    private static long revision(Long value) {
        if (value == null || value < 0) {
            throw new GatewayException(GatewayException.Code.INVALID, "请求必须包含非负的 expectedRevision");
        }
        return value;
    }

    public record Publish(String version, Long expectedRevision) {}
    public record Disable(Long expectedRevision) {}
    public record Search(String query, Integer limit) {}
    public record ErrorResponse(String code, String message) {}
}
