package com.example.enterpriseagent.infrastructure.gateway;

import com.example.enterpriseagent.toolgateway.adapter.ToolAdapter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** PostgreSQL 查询在强制只读事务里运行，租户参数来自网关身份。 */
public final class DatabaseToolAdapter implements ToolAdapter {
    private final ObjectMapper json;
    private final Map<String, DatabaseToolBinding> bindings;

    public DatabaseToolAdapter(ObjectMapper json, List<DatabaseToolBinding> bindings) {
        this.json = json;
        this.bindings = bindings.stream().collect(Collectors.toUnmodifiableMap(DatabaseToolBinding::name, Function.identity()));
    }

    @Override public Protocol protocol() { return Protocol.DATABASE; }
    @Override public Set<String> targetRefs() { return bindings.keySet(); }
    @Override public boolean configured(String targetRef) { return bindings.containsKey(targetRef); }
    @Override public boolean configuredForTenant(String tenant, String targetRef) {
        DatabaseToolBinding binding = bindings.get(targetRef);
        return binding != null && binding.allowedTenants().contains(tenant);
    }

    @Override
    public Outcome invoke(Call call) {
        if (!configuredForTenant(call.caller().tenantId(), call.targetRef())) {
            return new Outcome("FAILED", null, "DB_TOOL_NOT_CONFIGURED");
        }
        DatabaseToolBinding binding = bindings.get(call.targetRef());
        try {
            var params = new LinkedHashMap<String, Object>();
            json.readTree(call.arguments()).fields().forEachRemaining(field -> params.put(field.getKey(), scalar(field.getValue())));
            params.put("tenantId", call.caller().tenantId());
            params.put("principalId", call.caller().principalId());
            var manager = new DataSourceTransactionManager(binding.source());
            manager.setEnforceReadOnly(true);
            var tx = new TransactionTemplate(manager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.setReadOnly(true);
            tx.setTimeout(Math.max(1, (call.timeoutMs() + 999) / 1000));
            var jdbc = new NamedParameterJdbcTemplate(binding.source());
            String output = tx.execute(ignored -> {
                jdbc.queryForObject("SELECT set_config('statement_timeout', :budget, true)",
                        Map.of("budget", call.timeoutMs() + "ms"), String.class);
                return jdbc.execute(binding.sql(), params, statement -> {
                    statement.setMaxRows(binding.maxRows() + 1);
                    try (var rows = statement.executeQuery()) {
                        return encode(rows, binding.maxRows());
                    }
                });
            });
            return new Outcome("SUCCEEDED", output, null);
        } catch (QueryTimeoutException ex) {
            return new Outcome("UNKNOWN", null, "DB_TIMEOUT");
        } catch (LargeResult ex) {
            return new Outcome("FAILED", null, "DB_RESULT_LIMIT");
        } catch (DataAccessException | JsonProcessingException | IllegalArgumentException ex) {
            return new Outcome("FAILED", null, "DB_QUERY_FAILED");
        }
    }

    private String encode(ResultSet rows, int maxRows) throws SQLException {
        var result = json.createArrayNode();
        int bytes = 12;
        var columns = rows.getMetaData();
        while (rows.next()) {
            if (result.size() == maxRows) throw new LargeResult();
            var row = json.createObjectNode();
            for (int i = 1; i <= columns.getColumnCount(); i++) {
                if (columns.getColumnType(i) == Types.BINARY || columns.getColumnType(i) == Types.BLOB) {
                    throw new IllegalArgumentException("数据库工具仅返回标量文本与结构化结果");
                }
                Object raw = rows.getObject(i);
                Object value = raw == null || raw instanceof Number || raw instanceof Boolean ? raw : raw.toString();
                row.set(columns.getColumnLabel(i), json.valueToTree(value));
            }
            bytes += row.toString().getBytes(StandardCharsets.UTF_8).length + 1;
            if (bytes > 262_144) throw new LargeResult();
            result.add(row);
        }
        return json.createObjectNode().set("rows", result).toString();
    }

    private static Object scalar(JsonNode value) {
        if (value.isNull()) return null;
        if (value.isBoolean()) return value.booleanValue();
        if (value.isNumber()) return value.numberValue();
        if (value.isTextual()) return value.textValue();
        throw new IllegalArgumentException("数据库模板参数必须为标量");
    }

    private static final class LargeResult extends RuntimeException {}
}
