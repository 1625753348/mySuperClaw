package com.example.enterpriseagent.access.http;

import com.example.enterpriseagent.intent.IntentModel.Failure;
import com.example.enterpriseagent.intent.RuleAdministration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/admin/rules/{intent}/{definitionVersion}")
@Profile("storage & gateway & intent")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class PredefinedRulesController {
    private final RuleAdministration rules;

    public PredefinedRulesController(RuleAdministration rules) { this.rules = rules; }

    @GetMapping
    public List<RuleAdministration.Version> versions(JwtAuthenticationToken token, @PathVariable String intent, @PathVariable int definitionVersion) {
        return rules.versions(IntentController.actor(token), intent, definitionVersion);
    }

    @PutMapping("/versions/{version}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(JwtAuthenticationToken token, @PathVariable String intent, @PathVariable int definitionVersion,
                     @PathVariable int version, @RequestBody RuleAdministration.Spec spec) {
        rules.save(IntentController.actor(token), intent, definitionVersion, version, spec);
    }

    @PostMapping("/versions/{version}/publish")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void publish(JwtAuthenticationToken token, @PathVariable String intent, @PathVariable int definitionVersion,
                        @PathVariable int version) {
        rules.publish(IntentController.actor(token), intent, definitionVersion, version);
    }

    @ExceptionHandler(Failure.class)
    ResponseEntity<Map<String, String>> failed(Failure failure) {
        return ResponseEntity.status(failure.status()).body(Map.of("message", failure.getMessage()));
    }
}
