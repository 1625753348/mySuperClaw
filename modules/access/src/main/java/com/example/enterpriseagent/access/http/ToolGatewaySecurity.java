package com.example.enterpriseagent.access.http;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** HTTP 层只信任签名、发行方和受众都匹配的 Bearer JWT。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Profile("storage & (gateway | intent)")
public class ToolGatewaySecurity {

    @Bean
    JwtDecoder gatewayJwtDecoder(@Value("${enterprise.gateway.jwt.issuer}") String issuer,
                                 @Value("${enterprise.gateway.jwt.audience}") String audience,
                                 @Value("${enterprise.gateway.jwt.jwk-set-uri:}") String jwkSetUri) {
        if (issuer.isBlank() || audience.isBlank()) {
            throw new IllegalArgumentException("启用网关需要配置 JWT 发行方与受众");
        }
        NimbusJwtDecoder decoder = jwkSetUri.isBlank()
                ? NimbusJwtDecoder.withIssuerLocation(issuer).build()
                : NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        var audienceValidator = new JwtClaimValidator<List<String>>("aud", value -> value != null && value.contains(audience));
        var expirationRequired = new JwtClaimValidator<Instant>("exp", Objects::nonNull);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), audienceValidator, expirationRequired));
        return decoder;
    }

    @Bean
    SecurityFilterChain toolGatewayChain(HttpSecurity security, Environment environment) throws Exception {
        if (environment.acceptsProfiles(Profiles.of("local-console & gateway & intent"))) {
            security.authorizeHttpRequests(request ->
                    request.requestMatchers(HttpMethod.POST, "/v1/local-console/connect").permitAll());
        }
        return security
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; "
                                + "form-action 'none'; base-uri 'none'; frame-ancestors 'none'")))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(request -> request
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/tool-admin/index.html", "/tool-admin/admin.css",
                                "/tool-admin/admin.js", "/tool-admin/chat.js", "/tool-admin/workflows.js").permitAll()
                        .requestMatchers("/v1/admin/tools/**").hasAuthority("SCOPE_tools.admin")
                        .requestMatchers("/v1/admin/workflow-skills/**").hasAuthority("SCOPE_skills.admin")
                        .requestMatchers("/v1/admin/rules/**").hasAuthority("SCOPE_rules.admin")
                        .requestMatchers(HttpMethod.GET, "/v1/admin/intent-review/unmatched").hasAuthority("SCOPE_intents.review")
                        .requestMatchers(HttpMethod.GET, "/v1/admin/operations/**").hasAuthority("SCOPE_ops.read")
                        .requestMatchers(HttpMethod.POST, "/v1/admin/operations/**").hasAuthority("SCOPE_ops.write")
                        .requestMatchers(HttpMethod.GET, "/v1/monitors", "/v1/monitors/**").hasAuthority("SCOPE_monitors.read")
                        .requestMatchers(HttpMethod.PUT, "/v1/monitors/**").hasAuthority("SCOPE_monitors.write")
                        .requestMatchers(HttpMethod.POST, "/v1/monitors/**").hasAuthority("SCOPE_monitors.write")
                        .requestMatchers(HttpMethod.POST, "/v1/tool-callbacks/*").permitAll()
                        .requestMatchers("/v1/agent-skills", "/v1/agent-skills/**")
                        .access((authentication, context) -> {
                            var authorities = authentication.get().getAuthorities();
                            return new org.springframework.security.authorization.AuthorizationDecision(
                                    authorities.stream().anyMatch(a -> a.getAuthority().equals("SCOPE_agent.skills.use"))
                                    && authorities.stream().anyMatch(a -> a.getAuthority().equals("SCOPE_tools.invoke")));
                        })
                        .requestMatchers(HttpMethod.PUT, "/v1/memory/profile/*").hasAuthority("SCOPE_memory.profile.write")
                        .requestMatchers(HttpMethod.PUT, "/v1/memory/knowledge/*/versions/*").hasAuthority("SCOPE_memory.knowledge.write")
                        .requestMatchers("/v1/tools/approvals/**").hasAuthority("SCOPE_tools.approve")
                        .requestMatchers("/v1/tools/**").hasAuthority("SCOPE_tools.invoke")
                        .requestMatchers(HttpMethod.POST, "/v1/runs", "/v1/runs/*/cancel").hasAuthority("SCOPE_runs.submit")
                        .requestMatchers(HttpMethod.GET, "/v1/runs/**").hasAuthority("SCOPE_runs.read")
                        .requestMatchers(HttpMethod.POST, "/v1/conversations", "/v1/conversations/*/turns").hasAuthority("SCOPE_intents.submit")
                        .requestMatchers(HttpMethod.GET, "/v1/conversations", "/v1/conversations/*/turns", "/v1/conversations/*/turns/*",
                                "/v1/conversations/*/turns/*/artifacts").hasAuthority("SCOPE_intents.read")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(server -> server.jwt(jwt -> {}))
                .build();
    }
}
