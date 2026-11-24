package com.example.enterpriseagent.access.http;

import com.example.enterpriseagent.skill.SkillRegistry;
import com.example.enterpriseagent.skill.SkillRegistry.Actor;
import com.example.enterpriseagent.skill.SkillRegistry.Failure;
import com.example.enterpriseagent.skill.SkillRegistry.Published;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@Profile("storage & gateway")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/v1/admin/workflow-skills")
public final class WorkflowSkillController {
    private final SkillRegistry registry;

    public WorkflowSkillController(SkillRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public SkillRegistry.Page list(@AuthenticationPrincipal Jwt token,
                                   @RequestParam(defaultValue = "") String after,
                                   @RequestParam(defaultValue = "25") int limit) {
        return registry.list(actor(token), after, limit);
    }

    @PostMapping("/{key}/versions/{version}/publish")
    public Published publish(@AuthenticationPrincipal Jwt token, @PathVariable String key, @PathVariable int version,
                             @RequestBody Release release) {
        return registry.publish(actor(token), key, version, release.expectedRevision(), release.markdown());
    }

    @GetMapping("/{key}")
    public Published read(@AuthenticationPrincipal Jwt token, @PathVariable String key,
                          @RequestParam(required = false) Integer version) {
        return registry.get(actor(token), key, version);
    }

    @GetMapping("/resolve")
    public Published resolve(@AuthenticationPrincipal Jwt token, @RequestParam String command) {
        return registry.resolve(actor(token), command);
    }

    @ExceptionHandler(Failure.class)
    public ResponseEntity<Map<String, String>> failure(Failure ex) {
        return ResponseEntity.status(ex.status()).body(Map.of("message", ex.getMessage()));
    }

    private static Actor actor(Jwt token) {
        String tenant = token.getClaimAsString("tenant_id");
        String user = token.getSubject();
        String agent = token.getClaimAsString("agent_id");
        if (tenant == null || tenant.isBlank() || user == null || user.isBlank() || agent != null && agent.isBlank()) {
            throw new Failure(403, "缺少可信租户、主体或 Agent");
        }
        List<String> roles = token.getClaimAsStringList("roles");
        return new Actor(tenant, user, agent == null ? user : agent, roles == null ? List.of() : roles);
    }

    public record Release(Long expectedRevision, String markdown) {}
}
