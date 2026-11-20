package com.example.enterpriseagent.access.http;

import com.example.enterpriseagent.memory.store.MemoryAdministration;
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
@Profile("storage & gateway & memory-loop")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class MemoryController {
    private final MemoryAdministration memory;

    public MemoryController(MemoryAdministration memory) { this.memory = memory; }

    @PutMapping("/v1/memory/profile/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void profile(JwtAuthenticationToken token, @PathVariable String key, @RequestBody Preference preference) {
        var actor = IntentController.actor(token);
        memory.profile(actor.tenant(), actor.user(), key, preference.value(), preference.revision());
    }

    @PutMapping("/v1/memory/knowledge/{document}/versions/{version}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void publish(JwtAuthenticationToken token, @PathVariable UUID document, @PathVariable int version,
                        @RequestBody Knowledge knowledge) {
        memory.publish(IntentController.actor(token).tenant(), document, version, knowledge.sourceKey(), knowledge.chunks());
    }

    @ExceptionHandler({MemoryAdministration.Failure.class, com.example.enterpriseagent.intent.IntentModel.Failure.class})
    ResponseEntity<Map<String, String>> error(RuntimeException failure) {
        int status = failure instanceof MemoryAdministration.Failure error
                ? error.status() : ((com.example.enterpriseagent.intent.IntentModel.Failure) failure).status();
        return ResponseEntity.status(status).body(Map.of("message", failure.getMessage()));
    }

    public record Preference(String value, long revision) {}
    public record Knowledge(String sourceKey, List<String> chunks) {}
}
