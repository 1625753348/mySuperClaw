package com.example.enterpriseagent.infrastructure.workflow;

import com.example.enterpriseagent.skill.SkillRegistry;
import com.example.enterpriseagent.skill.compiler.SkillCompiler;
import com.example.enterpriseagent.skill.compiler.SkillCompiler.Manifest;
import com.example.enterpriseagent.skill.compiler.SkillCompiler.Node;
import com.example.enterpriseagent.toolgateway.GatewayCaller;
import com.example.enterpriseagent.toolgateway.GatewayException;
import com.example.enterpriseagent.toolgateway.registry.ToolContracts;
import com.example.enterpriseagent.toolgateway.registry.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 锁内发布，旧版本、工具版本和原始 Skill 文档保留用于后续恢复。 */
public final class PostgresSkillRegistry implements SkillRegistry {
    private static final String VERSIONS = """
            SELECT v.id,s.skill_key,v.version,s.revision,v.status,v.is_current,v.compiled_digest,v.compiled_dag::text
            FROM runtime.workflow_skill s
            JOIN runtime.workflow_skill_version v ON v.tenant_id=s.tenant_id AND v.skill_id=s.id
            """;
    private final JdbcClient db;
    private final TransactionOperations tx;
    private final ToolRegistry tools;
    private final ToolContracts contracts;
    private final SkillCompiler compiler;
    private final ObjectMapper json;

    public PostgresSkillRegistry(JdbcClient db, TransactionOperations tx, ToolRegistry tools, ToolContracts contracts, ObjectMapper json) {
        this.db = db;
        this.tx = tx;
        this.tools = tools;
        this.contracts = contracts;
        this.json = json;
        this.compiler = new SkillCompiler(json);
    }

    @Override
    public Published publish(Actor actor, String key, int version, Long expectedRevision, String markdown) {
        if (expectedRevision == null || expectedRevision < 0) throw new Failure(400, "需要有效的 expectedRevision");
        Manifest manifest = compiler.compile(markdown, key, version);
        try {
            String schema = contracts.definition(manifest.inputSchema());
            return tx.execute(ignored -> publishLocked(actor, manifest, expectedRevision, markdown, schema));
        } catch (GatewayException ex) {
            throw new Failure(ex.code() == GatewayException.Code.INVALID ? 400 : 409, "工具绑定失败：" + ex.getMessage());
        }
    }

    private Published publishLocked(Actor actor, Manifest spec, long expectedRevision, String markdown, String schema) {
        String tenant = actor.tenant();
        String key = spec.skillKey();
        db.sql("""
                INSERT INTO runtime.workflow_skill(tenant_id,id,skill_key,display_name,status) VALUES(?,?,?,?,'DRAFT')
                ON CONFLICT(tenant_id,skill_key) DO NOTHING
                """).params(tenant, UUID.randomUUID(), key, key).update();
        var skill = db.sql("SELECT id,revision FROM runtime.workflow_skill WHERE tenant_id=? AND skill_key=? FOR UPDATE")
                .params(tenant, key).query((rs, row) -> new Skill(rs.getObject(1, UUID.class), rs.getLong(2))).single();
        String source = "sha256:" + ToolContracts.digest(markdown);
        var old = db.sql("SELECT source_ref FROM runtime.workflow_skill_version WHERE tenant_id=? AND skill_id=? AND version=?")
                .params(tenant, skill.id(), spec.version()).query(String.class).optional();
        if (old.isPresent()) {
            if (old.get().equals(source)) return get(actor, key, spec.version());
            throw new Failure(409, "此版本已存在，请使用新版本号");
        }
        if (skill.revision() != expectedRevision) throw new Failure(409, "Skill 修订号已变更");
        int highest = db.sql("SELECT coalesce(max(version),0) FROM runtime.workflow_skill_version WHERE tenant_id=? AND skill_id=?")
                .params(tenant, skill.id()).query(Integer.class).single();
        if (highest >= spec.version()) throw new Failure(409, "新版本号必须大于已有版本");

        Map<String, ToolBinding> bindings = bindTools(actor, spec);
        UUID definition = bindDefinition(tenant, spec.intentDefinition(), schema);
        var compiled = json.valueToTree(spec);
        ((com.fasterxml.jackson.databind.node.ObjectNode) compiled).set("toolBindings", json.valueToTree(bindings));
        String digest = ToolContracts.digest(compiled.toString());
        UUID id = UUID.randomUUID();
        db.sql("""
                UPDATE runtime.workflow_skill_version SET is_current=false,status='RETIRED'
                WHERE tenant_id=? AND skill_id=? AND is_current
                """).params(tenant, skill.id()).update();
        db.sql("""
                INSERT INTO runtime.workflow_skill_version(tenant_id,id,skill_id,version,intent_definition_id,execution_mode,
                    status,is_current,source_ref,source_markdown,admission_policy_ref,compiled_dag,compiled_digest,
                    reviewed_by,reviewed_at,published_by,published_at)
                VALUES(?,?,?,?,?,?,'PUBLISHED',true,?,?,?,?::jsonb,?,?,now(),?,now())
                """).params(tenant, id, skill.id(), spec.version(), definition, spec.executionMode(),
                source, markdown, spec.admissionPolicy(), compiled.toString(), digest, actor.user(), actor.user()).update();
        bindings.forEach((node, binding) -> db.sql("""
                INSERT INTO runtime.workflow_skill_tool(tenant_id,skill_version_id,node_key,tool_id,tool_version_id)
                VALUES(?,?,?,?,?)
                """).params(tenant, id, node, binding.toolId(), binding.versionId()).update());
        db.sql("DELETE FROM runtime.workflow_skill_trigger WHERE tenant_id=? AND skill_id=?").params(tenant, skill.id()).update();
        for (var trigger : spec.triggers()) {
            if (db.sql("""
                    INSERT INTO runtime.workflow_skill_trigger(tenant_id,trigger_kind,normalized_key,skill_id)
                    VALUES(?,?,?,?) ON CONFLICT DO NOTHING
                    """).params(tenant, trigger.kind(), trigger.key(), skill.id()).update() != 1) {
                throw new Failure(409, "命令已绑定其他 Skill：" + trigger.key());
            }
        }
        long revision = skill.revision() + 1;
        db.sql("UPDATE runtime.workflow_skill SET status='ACTIVE',revision=? WHERE tenant_id=? AND id=?")
                .params(revision, tenant, skill.id()).update();
        db.sql("""
                INSERT INTO eventing.outbox(tenant_id,event_id,event_type,aggregate_type,aggregate_id,aggregate_version,
                    stream,trace_id,correlation_id,payload)
                VALUES(?,?,'WorkflowSkillPublished','workflow-skill',?,?,'agent:events:workflow-skill',?,?,?::jsonb)
                """).params(tenant, UUID.randomUUID(), skill.id(), revision, ToolRegistry.trace(), id.toString(),
                json.valueToTree(Map.of("skillKey", key, "version", spec.version(), "skillVersionId", id, "digest", digest)).toString()).update();
        return new Published(id, key, spec.version(), revision, "PUBLISHED", true, digest, compiled);
    }

    private UUID bindDefinition(String tenant, String definition, String schema) {
        String[] parts = definition.split("[.@]");
        int version = Integer.parseInt(parts[2]);
        db.sql("""
                INSERT INTO intent.definition(tenant_id,id,domain,action,version,status,parameter_schema)
                VALUES(?,?,?,?,?,'PUBLISHED',?::jsonb) ON CONFLICT DO NOTHING
                """).params(tenant, UUID.randomUUID(), parts[0], parts[1], version, schema).update();
        return db.sql("""
                SELECT id FROM intent.definition WHERE tenant_id=? AND domain=? AND action=? AND version=?
                    AND status='PUBLISHED' AND parameter_schema=?::jsonb
                """).params(tenant, parts[0], parts[1], version, schema).query(UUID.class).optional()
                .orElseThrow(() -> new Failure(409, "意图定义版本已有不同契约或未发布"));
    }

    private Map<String, ToolBinding> bindTools(Actor actor, Manifest manifest) {
        var caller = new GatewayCaller(actor.tenant(), actor.user(), actor.agent(), actor.roles());
        var bindings = new LinkedHashMap<String, ToolBinding>();
        for (Node node : manifest.nodes().stream().filter(node -> node.tool() != null)
                .sorted(java.util.Comparator.comparing(node -> node.tool().replace('/', '_'))).toList()) {
            String[] ref = node.tool().split("@");
            String name = ref[0].replace('/', '_');
            var tool = ToolRegistry.active(tools.lock(caller, name));
            var version = ToolRegistry.published(tools.resolve(caller, tool, ref[1], null));
            tools.endpoint(caller, version);
            validateTool(node, read(version.inputSchema()), read(version.outputSchema()));
            bindings.put(node.id(), new ToolBinding(name, tool.id(), ref[1], version.id(),
                    ToolContracts.digest(version.inputSchema() + "\n" + version.outputSchema())));
        }
        return bindings;
    }

    private static void validateTool(Node node, JsonNode input, JsonNode output) {
        var required = new HashSet<String>();
        input.path("required").forEach(field -> required.add(field.asText()));
        var fields = input.path("properties");
        var response = output.path("properties");
        var outputRequired = new HashSet<String>();
        output.path("required").forEach(field -> outputRequired.add(field.asText()));
        if (!"object".equals(input.path("type").asText()) || !node.inputs().keySet().containsAll(required)
                || !node.inputs().keySet().stream().allMatch(key -> "string".equals(fields.path(key).path("type").asText()))
                || !"object".equals(output.path("type").asText()) || !outputRequired.containsAll(java.util.Set.of("complete", "items"))
                || !"boolean".equals(response.path("complete").path("type").asText())
                || !"array".equals(response.path("items").path("type").asText())) {
            throw new Failure(409, "工具输入或证据输出 Schema 与节点不匹配：" + node.id());
        }
    }

    @Override
    public Published get(Actor actor, String key, Integer version) {
        var query = db.sql(VERSIONS + " WHERE s.tenant_id=:tenant AND s.skill_key=:key AND "
                + (version == null ? "s.status='ACTIVE' AND v.is_current" : "v.version=:version"))
                .param("tenant", actor.tenant()).param("key", key);
        if (version != null) query.param("version", version);
        return found(query);
    }

    @Override
    public Published resolve(Actor actor, String command) {
        return found(db.sql(VERSIONS + """
                JOIN runtime.workflow_skill_trigger t ON t.tenant_id=s.tenant_id AND t.skill_id=s.id
                WHERE s.tenant_id=? AND t.trigger_kind='COMMAND' AND t.normalized_key=? AND s.status='ACTIVE' AND v.is_current
                """).params(actor.tenant(), command));
    }

    private Published found(JdbcClient.StatementSpec query) {
        return query.query((rs, row) -> new Published(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getInt(3), rs.getLong(4), rs.getString(5), rs.getBoolean(6), rs.getString(7), read(rs.getString(8))))
                .optional().orElseThrow(() -> new Failure(404, "未找到 Skill 版本"));
    }

    private JsonNode read(String value) {
        if (value == null) return null;
        try {
            return json.readTree(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Skill 契约无法读取", ex);
        }
    }

    private record Skill(UUID id, long revision) {}
    private record ToolBinding(String toolName, UUID toolId, String version, UUID versionId, String schemaDigest) {}
}
