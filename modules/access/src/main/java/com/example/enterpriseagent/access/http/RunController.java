package com.example.enterpriseagent.access.http;

import com.example.enterpriseagent.access.sse.RunSse;
import com.example.enterpriseagent.runtime.RunFailure;
import com.example.enterpriseagent.runtime.RunManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.util.UUID;

import static com.example.enterpriseagent.runtime.RunModel.*;

@RestController
@Profile("storage & gateway & runtime")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class RunController implements AutoCloseable {
    private final RunManager manager;
    private final RunSse events;
    private final ObjectMapper json;

    public RunController(RunManager manager, ObjectMapper json) {
        this.manager = manager;
        this.events = new RunSse(manager);
        this.json = json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    @PostMapping(value = "/v1/runs", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<View> submit(Authentication caller,
            @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody String body) {
        if (body.length() > 180_000) throw new RunFailure(RunFailure.Code.INVALID, "长任务请求过大");
        Request request;
        try {
            request = json.readValue(body, Request.class);
            if (request == null) throw new RunFailure(RunFailure.Code.INVALID, "需要长任务请求对象");
        } catch (JsonProcessingException ex) {
            throw new RunFailure(RunFailure.Code.INVALID, "长任务请求不是有效 JSON");
        }
        if (request.source() != null && caller.getAuthorities().stream().noneMatch(a -> "SCOPE_tools.invoke".equals(a.getAuthority()))) {
            throw new RunFailure(RunFailure.Code.FORBIDDEN, "工具数据源要求 tools.invoke 权限");
        }
        View view = manager.submit(actor(caller), request, key);
        return ResponseEntity.accepted().location(URI.create("/v1/runs/" + view.runId())).body(view);
    }

    @GetMapping("/v1/runs/{runId}")
    public View status(Authentication caller, @PathVariable UUID runId) { return manager.view(actor(caller), runId); }

    @GetMapping("/v1/runs/{runId}/result")
    public JsonNode result(Authentication caller, @PathVariable UUID runId) { return manager.result(actor(caller), runId); }

    @PostMapping("/v1/runs/{runId}/cancel")
    public ResponseEntity<View> cancel(Authentication caller, @PathVariable UUID runId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        View view = manager.cancel(actor(caller), runId, key);
        return "CANCEL_REQUESTED".equals(view.status()) ? ResponseEntity.accepted().body(view) : ResponseEntity.ok(view);
    }

    @GetMapping(value = "/v1/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(Authentication caller, @PathVariable UUID runId,
            @RequestHeader(value = "Last-Event-ID", required = false) String last,
            @RequestParam(defaultValue = "0") String after) {
        long cursor;
        try {
            cursor = Long.parseLong(last == null || last.isBlank() ? after : last);
        } catch (NumberFormatException ex) {
            throw new RunFailure(RunFailure.Code.INVALID, "事件游标无效");
        }
        var emitter = events.open(actor(caller), runId, cursor, ((JwtAuthenticationToken) caller).getToken().getExpiresAt());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering", "no").body(emitter);
    }

    @ExceptionHandler(RunFailure.class)
    public ResponseEntity<ErrorResponse> error(RunFailure error) {
        HttpStatus status = switch (error.code()) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case CONFLICT -> HttpStatus.CONFLICT;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case BUSY -> HttpStatus.TOO_MANY_REQUESTS;
        };
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(error.code().name(), error.getMessage()));
    }

    private static Actor actor(Authentication caller) {
        if (!(caller instanceof JwtAuthenticationToken authenticated)) throw new RunFailure(RunFailure.Code.FORBIDDEN, "缺少运行身份");
        var token = authenticated.getToken();
        String tenant = token.getClaimAsString("tenant_id");
        String user = token.getSubject();
        String agent = token.getClaimAsString("agent_id");
        if (tenant == null || tenant.isBlank() || user == null || user.isBlank() || (agent != null && agent.isBlank())) {
            throw new RunFailure(RunFailure.Code.FORBIDDEN, "缺少运行所需的租户或主体");
        }
        return new Actor(tenant, user, agent == null ? user : agent, token.getClaimAsStringList("roles"));
    }

    private record ErrorResponse(String code, String message) {}
    @Override public void close() { events.close(); }
}
