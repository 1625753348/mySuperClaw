package com.example.enterpriseagent.access.http;

import com.example.enterpriseagent.toolgateway.GatewayException;
import com.example.enterpriseagent.toolgateway.execution.ToolCallbacks;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/** 非 JWT 的独立入口，服务层使用已注册供应商的原始请求签名认证。 */
@RestController
@RequestMapping("/v1/tool-callbacks")
@Profile("storage & gateway")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class ToolCallbackController {
    private final ToolCallbacks callbacks;

    public ToolCallbackController(ToolCallbacks callbacks) {
        this.callbacks = callbacks;
    }

    @PostMapping(path = "/{provider}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void receive(@PathVariable String provider,
                        @RequestHeader(value = "X-Callback-Timestamp", required = false) String timestamp,
                        @RequestHeader(value = "X-Callback-Signature", required = false) String signature,
                        HttpServletRequest request) throws IOException {
        callbacks.accept(provider, timestamp, signature, request.getInputStream().readNBytes(ToolCallbacks.MAX_BODY_BYTES + 1));
    }

    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<Void> failed(GatewayException error) {
        return ResponseEntity.status(switch (error.code()) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case FORBIDDEN, POLICY_REQUIRED -> HttpStatus.FORBIDDEN;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case UPSTREAM -> HttpStatus.BAD_GATEWAY;
        }).build();
    }
}
