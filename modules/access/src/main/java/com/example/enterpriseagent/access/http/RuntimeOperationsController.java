package com.example.enterpriseagent.access.http;

import com.example.enterpriseagent.runtime.RunFailure;
import com.example.enterpriseagent.runtime.RuntimeOperations;
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
@RequestMapping("/v1/admin/operations")
@Profile("storage & gateway & runtime")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class RuntimeOperationsController {
    private final RuntimeOperations operations;

    public RuntimeOperationsController(RuntimeOperations operations) { this.operations = operations; }

    @GetMapping("/outbox")
    public List<RuntimeOperations.Delivery> deliveries(JwtAuthenticationToken token,
            @RequestParam(defaultValue = "true") boolean dead, @RequestParam(defaultValue = "25") int limit) {
        return operations.deliveries(IntentController.actor(token).tenant(), dead, limit);
    }

    @GetMapping("/reconciliation")
    public List<RuntimeOperations.UncertainRun> uncertain(JwtAuthenticationToken token, @RequestParam(defaultValue = "25") int limit) {
        return operations.uncertainRuns(IntentController.actor(token).tenant(), limit);
    }

    @PostMapping("/outbox/{event}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void replay(JwtAuthenticationToken token, @PathVariable UUID event, @RequestBody Resolution request) {
        var actor = IntentController.actor(token);
        operations.replay(actor.tenant(), actor.user(), event, request.reason());
    }

    @PostMapping("/reconciliation/{run}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(JwtAuthenticationToken token, @PathVariable UUID run, @RequestBody Resolution request) {
        var actor = IntentController.actor(token);
        operations.close(actor.tenant(), actor.user(), run, request.reason());
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

    public record Resolution(String reason) {}
}
