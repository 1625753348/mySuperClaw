package com.example.enterpriseagent.access.http;

import com.example.enterpriseagent.intent.IntentModel.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@Profile("storage & intent")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class IntentController {
    private final Store intent;

    public IntentController(Store intent) { this.intent = intent; }

    @PostMapping("/v1/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public Conversation create(Authentication auth) { return new Conversation(intent.create(actor(auth))); }

    @PostMapping("/v1/conversations/{conversationId}/turns")
    public Receipt turn(Authentication auth, @PathVariable UUID conversationId,
                        @RequestHeader(value = "Idempotency-Key", required = false) String key, @RequestBody Request request) {
        return intent.turn(actor(auth), conversationId, request, key);
    }

    @GetMapping("/v1/conversations/{conversationId}/turns/{turnId}")
    public Receipt receipt(Authentication auth, @PathVariable UUID conversationId, @PathVariable UUID turnId) {
        return intent.receipt(actor(auth), conversationId, turnId);
    }

    @ExceptionHandler(Failure.class)
    public ResponseEntity<ErrorResponse> error(Failure failure) {
        return ResponseEntity.status(failure.status()).body(new ErrorResponse(failure.getMessage()));
    }

    private static Actor actor(Authentication auth) {
        if (!(auth instanceof JwtAuthenticationToken jwt)) throw new Failure(403, "缺少可信身份");
        String tenant = jwt.getToken().getClaimAsString("tenant_id");
        String user = jwt.getToken().getSubject();
        String agent = jwt.getToken().getClaimAsString("agent_id");
        if (tenant == null || tenant.isBlank() || user == null || user.isBlank() || agent != null && agent.isBlank()) {
            throw new Failure(403, "缺少租户、主体或 Agent");
        }
        Set<String> scopes = auth.getAuthorities().stream().map(GrantedAuthority::getAuthority)
                .filter(value -> value.startsWith("SCOPE_")).map(value -> value.substring(6)).collect(Collectors.toUnmodifiableSet());
        List<String> roles = jwt.getToken().getClaimAsStringList("roles");
        return new Actor(tenant, user, agent == null ? user : agent, scopes, roles == null ? Set.of() : Set.copyOf(roles));
    }

    public record Conversation(UUID conversationId) {}
    public record ErrorResponse(String message) {}
}
