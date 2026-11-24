package com.example.enterpriseagent.infrastructure.gateway;

import com.example.enterpriseagent.toolgateway.execution.ToolSecrets;
import com.example.enterpriseagent.toolgateway.adapter.HttpToolAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 从部署时配置的 Vault KV v2 路径读取工具 Bearer 凭证。 */
public final class VaultSecretReader implements ToolSecrets.VaultReader {
    private static final int MAX_RESPONSE = 32_768;
    private final Settings settings;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    public VaultSecretReader(Settings settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
    }

    @Override
    public String read(String path) {
        URI uri = settings.endpoint().resolve("/v1/" + settings.mount() + "/data/" + path);
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).header("Accept", "application/json")
                .header("X-Vault-Token", settings.token());
        if (settings.namespace() != null) request.header("X-Vault-Namespace", settings.namespace());
        var exchange = client.sendAsync(request.GET().build(), ignored -> HttpToolAdapter.boundedBody());
        try {
            var response = exchange.get(3, TimeUnit.SECONDS);
            if (response.statusCode() != 200 || response.body().length > MAX_RESPONSE) throw ToolSecrets.unavailable();
            var payload = json.readTree(response.body());
            return payload == null ? null : payload.path("data").path("data").path("bearerToken").textValue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ToolSecrets.unavailable();
        } catch (IOException | ExecutionException | TimeoutException ex) {
            throw ToolSecrets.unavailable();
        } finally {
            if (!exchange.isDone()) exchange.cancel(true);
        }
    }

    public record Settings(URI endpoint, String token, String mount, String namespace, boolean allowLocalHttp) {
        public Settings {
            if (endpoint == null || endpoint.getHost() == null || endpoint.getRawUserInfo() != null
                    || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                    || !("".equals(endpoint.getRawPath()) || "/".equals(endpoint.getRawPath()))
                    || !("https".equals(endpoint.getScheme()) || allowLocalHttp && "http".equals(endpoint.getScheme())
                        && ("127.0.0.1".equals(endpoint.getHost()) || "[::1]".equals(endpoint.getHost())))
                    || token == null || !token.matches("[A-Za-z0-9._~-]{1,8192}")
                    || (namespace != null && !namespace.matches("[A-Za-z0-9_/-]{1,160}"))) {
                throw new IllegalArgumentException("Vault 配置需要可信源地址及认证凭证");
            }
            mount = mount == null ? "secret" : mount;
            if (!mount.matches("[A-Za-z0-9_-]{1,80}")) throw new IllegalArgumentException("Vault 挂载点无效");
        }

        @Override public String toString() { return "[managed Vault settings]"; }
    }
}
