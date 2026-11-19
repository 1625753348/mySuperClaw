package com.example.enterpriseagent.access.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
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
@Profile("storage & gateway")
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
    SecurityFilterChain toolGatewayChain(HttpSecurity security) throws Exception {
        return security
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; "
                                + "form-action 'none'; base-uri 'none'; frame-ancestors 'none'")))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(request -> request
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/tool-admin/index.html", "/tool-admin/admin.css",
                                "/tool-admin/admin.js").permitAll()
                        .requestMatchers("/v1/admin/tools/**").hasAuthority("SCOPE_tools.admin")
                        .requestMatchers("/v1/tools/approvals/**").hasAuthority("SCOPE_tools.approve")
                        .requestMatchers("/v1/tools/**").hasAuthority("SCOPE_tools.invoke")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(server -> server.jwt(jwt -> {}))
                .build();
    }
}
