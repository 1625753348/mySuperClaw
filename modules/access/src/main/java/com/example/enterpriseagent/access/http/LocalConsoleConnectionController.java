package com.example.enterpriseagent.access.http;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.regex.Pattern;

/** 本机测试入口。连接码仅用于一次性交换同一控制台的两枚凭证。 */
@RestController
@Profile("local-console & storage & gateway & intent")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public final class LocalConsoleConnectionController {
    private static final String LOOPBACK = "127.0.0.1";
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final Path directory;

    public LocalConsoleConnectionController(@Value("${enterprise.local-console.directory}") String directory,
                                            @Value("${server.address:}") String bindAddress) {
        if (!LOOPBACK.equals(bindAddress) || directory.isBlank()) {
            throw new IllegalArgumentException("本机控制台需要绑定 127.0.0.1 并指定凭证目录");
        }
        this.directory = Path.of(directory);
    }

    @PostMapping(path = "/v1/local-console/connect", consumes = "application/json")
    public ResponseEntity<Credentials> connect(@RequestBody ConnectionRequest input, HttpServletRequest request) {
        String origin = "http://" + LOOPBACK + ":" + request.getLocalPort();
        if (!LOOPBACK.equals(request.getRemoteAddr()) || !LOOPBACK.equals(request.getLocalAddr())
                || !origin.equals(request.getHeader("Origin"))) {
            return failure(HttpStatus.FORBIDDEN);
        }
        if (input.code() == null || !CODE.matcher(input.code()).matches()) {
            return failure(HttpStatus.GONE);
        }

        Path ticket = directory.resolve("connect-" + input.code() + ".ticket");
        try {
            long expiresAt = Long.parseLong(Files.readString(ticket).trim());
            if (Instant.now().getEpochSecond() >= expiresAt) {
                return failure(HttpStatus.GONE);
            }
            String management = Files.readString(directory.resolve("管理凭证.txt")).trim();
            String conversation = Files.readString(directory.resolve("会话凭证.txt")).trim();
            if (management.isBlank() || conversation.isBlank()) {
                return failure(HttpStatus.SERVICE_UNAVAILABLE);
            }
            // 只有成功删除连接码的请求才能获得凭证，重复或并发请求会得到 410。
            Files.delete(ticket);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new Credentials(management, conversation));
        } catch (NoSuchFileException | NumberFormatException ignored) {
            return failure(HttpStatus.GONE);
        } catch (IOException ignored) {
            return failure(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private static ResponseEntity<Credentials> failure(HttpStatus status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).build();
    }

    public record ConnectionRequest(String code) {}

    public record Credentials(String managementToken, String conversationToken) {}
}
