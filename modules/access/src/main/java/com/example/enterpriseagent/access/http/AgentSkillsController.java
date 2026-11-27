package com.example.enterpriseagent.access.http;

import com.example.enterpriseagent.skill.agent.AgentSkills;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@Profile("storage & gateway & agent-skills")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/v1/agent-skills")
public final class AgentSkillsController {
    private final AgentSkills skills;

    public AgentSkillsController(AgentSkills skills) { this.skills = skills; }

    @GetMapping
    public List<AgentSkills.Summary> list(@AuthenticationPrincipal Jwt jwt) {
        return skills.list(actor(jwt));
    }

    @PostMapping("/runs")
    public ResponseEntity<AgentSkills.Result> run(@AuthenticationPrincipal Jwt jwt,
            @RequestBody AgentSkills.Request request, @RequestHeader("Idempotency-Key") String key) {
        var result = skills.run(actor(jwt), request, key);
        int status = List.of("RUNNING", "WAITING_APPROVAL", "WAITING_EXTERNAL", "QUEUED", "RETRY_WAIT")
                .contains(result.status()) ? 202 : 200;
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<AgentSkills.Result> status(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(skills.status(actor(jwt), id));
    }

    @ExceptionHandler(AgentSkills.Failure.class)
    public ResponseEntity<Map<String, String>> failure(AgentSkills.Failure failure) {
        return ResponseEntity.status(failure.status()).cacheControl(CacheControl.noStore())
                .body(Map.of("message", failure.getMessage()));
    }

    private static AgentSkills.Actor actor(Jwt jwt) {
        String agent = jwt.getClaimAsString("agent_id");
        return new AgentSkills.Actor(jwt.getClaimAsString("tenant_id"), jwt.getSubject(),
                agent == null ? jwt.getSubject() : agent, jwt.getClaimAsStringList("roles"));
    }
}
