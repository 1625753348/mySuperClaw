package com.example.enterpriseagent.access.http;

import com.example.enterpriseagent.runtime.MonitorLoop;
import com.example.enterpriseagent.runtime.RunFailure;
import com.example.enterpriseagent.runtime.RunModel.Actor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1/monitors")
@Profile("storage & gateway & runtime")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class MonitorController {
    private final MonitorLoop monitors;

    public MonitorController(MonitorLoop monitors) { this.monitors = monitors; }

    @PutMapping("/{id}")
    public MonitorLoop.View create(JwtAuthenticationToken token, @PathVariable UUID id, @RequestBody MonitorLoop.Spec spec) {
        var actor = actor(token, true);
        if (spec != null && spec.remediationSop() != null) requireRunScope(token);
        return monitors.create(actor, id, spec);
    }

    @GetMapping
    public List<MonitorLoop.View> list(JwtAuthenticationToken token) { return monitors.list(actor(token, false)); }

    @GetMapping("/{id}")
    public MonitorLoop.View get(JwtAuthenticationToken token, @PathVariable UUID id) {
        return monitors.get(actor(token, false), id);
    }

    @GetMapping("/{id}/ticks")
    public List<MonitorLoop.Tick> history(JwtAuthenticationToken token, @PathVariable UUID id,
            @RequestParam(defaultValue = "9223372036854775807") long before) {
        return monitors.history(actor(token, false), id, before);
    }

    @GetMapping("/{id}/deliveries")
    public List<MonitorLoop.Delivery> deliveries(JwtAuthenticationToken token, @PathVariable UUID id,
            @RequestParam(defaultValue = "9223372036854775807") long before) {
        return monitors.deliveries(actor(token, false), id, before);
    }

    @PostMapping("/{id}/pause")
    public MonitorLoop.View pause(JwtAuthenticationToken token, @PathVariable UUID id) {
        return monitors.setPaused(actor(token, false), id, true);
    }

    @PostMapping("/{id}/resume")
    public MonitorLoop.View resume(JwtAuthenticationToken token, @PathVariable UUID id) {
        var actor = actor(token, true);
        if (monitors.get(actor, id).workflowSkillVersionId() != null) requireRunScope(token);
        return monitors.setPaused(actor, id, false);
    }

    private static void requireRunScope(JwtAuthenticationToken token) {
        if (!IntentController.actor(token).scopes().contains("runs.submit")) {
            throw new RunFailure(RunFailure.Code.FORBIDDEN, "启用监控 SOP 需要 runs.submit 权限");
        }
    }

    private static Actor actor(JwtAuthenticationToken token, boolean invoke) {
        var identity = IntentController.actor(token);
        if (invoke && !identity.scopes().contains("tools.invoke")) {
            throw new RunFailure(RunFailure.Code.FORBIDDEN, "启动监控需要 tools.invoke 权限");
        }
        return new Actor(identity.tenant(), identity.user(), identity.agent(), List.copyOf(identity.roles()));
    }

    @ExceptionHandler(RunFailure.class)
    ResponseEntity<Map<String, String>> failed(RunFailure failure) {
        var status = switch (failure.code()) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case CONFLICT -> HttpStatus.CONFLICT;
            case BUSY -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).body(Map.of("message", failure.getMessage()));
    }

    @ExceptionHandler(com.example.enterpriseagent.intent.IntentModel.Failure.class)
    ResponseEntity<Map<String, String>> identity(com.example.enterpriseagent.intent.IntentModel.Failure failure) {
        return ResponseEntity.status(failure.status()).body(Map.of("message", failure.getMessage()));
    }
}
