package com.example.enterpriseagent.infrastructure.intent;

import com.example.enterpriseagent.intent.IntentEngine;
import com.example.enterpriseagent.intent.IntentModel.Actor;
import com.example.enterpriseagent.intent.IntentModel.Analysis;
import com.example.enterpriseagent.intent.IntentModel.Failure;
import com.example.enterpriseagent.intent.IntentModel.Outcome;
import com.example.enterpriseagent.intent.ResponseComposer;
import com.example.enterpriseagent.intent.RuleAdministration;
import com.example.enterpriseagent.toolgateway.GatewayException;
import com.example.enterpriseagent.toolgateway.registry.ToolContracts;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 已发布规则在 Intent 准入增加限制，最后仍由 ToolGateway 执行治理。 */
public final class PostgresRules implements RuleAdministration {
    private final JdbcClient db;
    private final TransactionOperations tx;
    private final ObjectMapper json;
    private final ToolContracts contracts;
    private final IntentEngine engine;

    public PostgresRules(JdbcClient db, TransactionOperations tx, ObjectMapper json, ToolContracts contracts, IntentEngine engine) {
        this.db = db;
        this.tx = tx;
        this.json = json;
        this.contracts = contracts;
        this.engine = engine;
    }

    @Override public List<Version> versions(Actor actor, String intent, int definitionVersion) {
        definition(intent, definitionVersion);
        return db.sql("""
                SELECT version,status,spec::text FROM intent.predefined_rule
                WHERE tenant_id=? AND intent_key=? AND definition_version=?
                ORDER BY version DESC LIMIT 50
                """).params(actor.tenant(), intent, definitionVersion).query((rs, row) ->
                new Version(rs.getInt(1), rs.getString(2), decode(rs.getString(3)))).list();
    }

    @Override public void save(Actor actor, String intent, int definitionVersion, int version, Spec spec) {
        var definition = definition(intent, definitionVersion);
        if (version < 1 || spec == null || spec.parameters() == null || spec.safetyBoundary() == null
                || spec.clarification() == null || spec.clarification().length() > 500) throw new Failure(400, "规则参数无效");
        var boundary = spec.safetyBoundary();
        if (!"READ_ONLY".equals(boundary.effect()) || !"TENANT".equals(boundary.dataScope()) || !"INHERIT".equals(boundary.approval())
                || boundary.constraints() == null || boundary.constraints().length() > 500 || boundary.allowedRoles() == null
                || boundary.allowedRoles().size() > 20
                || boundary.allowedRoles().stream().anyMatch(role -> role == null || !role.matches("[A-Za-z0-9_.:-]{1,64}"))) {
            throw new Failure(400, "规则须限定为租户内只读，沿用 ToolGateway 审批");
        }
        var schema = json.valueToTree(spec.parameters());
        var fields = new java.util.HashSet<>(definition.required());
        fields.addAll(definition.optional());
        Set<String> supported = Set.of("type", "properties", "required", "additionalProperties");
        if (!"object".equals(schema.path("type").asText()) || !schema.path("properties").isObject()
                || schema.has("required") && !schema.path("required").isArray()
                || schema.properties().stream().anyMatch(entry -> !supported.contains(entry.getKey()))
                || schema.path("properties").properties().stream().anyMatch(entry -> !fields.contains(entry.getKey()))
                || java.util.stream.StreamSupport.stream(schema.path("required").spliterator(), false)
                    .anyMatch(field -> !field.isTextual() || !fields.contains(field.asText()))) {
            throw new Failure(400, "规则参数须使用已登记的意图字段");
        }
        try { contracts.definition(schema); }
        catch (GatewayException error) { throw new Failure(400, error.getMessage()); }
        int saved = db.sql("""
                INSERT INTO intent.predefined_rule(tenant_id,intent_key,definition_version,version,status,spec,created_by)
                VALUES(?,?,?,?,'DRAFT',?::jsonb,?) ON CONFLICT DO NOTHING
                """).params(actor.tenant(), intent, definitionVersion, version, json.valueToTree(spec).toString(), actor.user()).update();
        if (saved != 1) throw new Failure(409, "规则版本已存在，请使用新版本");
    }

    @Override public void publish(Actor actor, String intent, int definitionVersion, int version) {
        definition(intent, definitionVersion);
        if (version < 1) throw new Failure(400, "规则版本无效");
        tx.executeWithoutResult(ignored -> {
            String lock = actor.tenant().length() + ":" + actor.tenant() + ":" + intent + ":" + definitionVersion;
            db.sql("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(?,0))").param(lock).query(Integer.class).single();
            int highest = db.sql("""
                    SELECT coalesce(max(version),0) FROM intent.predefined_rule
                    WHERE tenant_id=? AND intent_key=? AND definition_version=? AND status IN ('PUBLISHED','RETIRED')
                    """).params(actor.tenant(), intent, definitionVersion).query(Integer.class).single();
            if (version <= highest) throw new Failure(409, "新发布版本需要大于历史版本");
            db.sql("""
                    UPDATE intent.predefined_rule SET status='RETIRED'
                    WHERE tenant_id=? AND intent_key=? AND definition_version=? AND status='PUBLISHED'
                    """).params(actor.tenant(), intent, definitionVersion).update();
            int count = db.sql("""
                    UPDATE intent.predefined_rule SET status='PUBLISHED',published_by=?,published_at=now()
                    WHERE tenant_id=? AND intent_key=? AND definition_version=? AND version=? AND status='DRAFT'
                    """).params(actor.user(), actor.tenant(), intent, definitionVersion, version).update();
            if (count != 1) throw new Failure(404, "规则草稿不存在");
        });
    }

    public Admission evaluate(Actor actor, Outcome outcome) {
        if (outcome.ir() == null) return null;
        Version rule = active(actor, outcome.intent(), outcome.ir().definitionVersion());
        if (rule == null) return null;
        var policy = rule.spec().safetyBoundary();
        String reason = !policy.allowedRoles().isEmpty() && policy.allowedRoles().stream().noneMatch(actor.roles()::contains)
                ? "RULE_ROLE_DENIED" : !contracts.matches(json.valueToTree(rule.spec().parameters()).toString(),
                    json.valueToTree(outcome.ir().arguments())) ? "RULE_PARAMETERS_INVALID" : null;
        return new Admission(rule.version(), reason);
    }

    public Analysis clarify(Actor actor, Analysis analysis) {
        var outcomes = new ArrayList<>(analysis.outcomes());
        boolean changed = false;
        for (int i = 0; i < outcomes.size(); i++) {
            Outcome item = outcomes.get(i);
            if (!"DIRECT".equals(item.route()) || !"NEEDS_CLARIFICATION".equals(item.status()) || item.taskId() == null) continue;
            var task = analysis.state().tasks().stream().filter(candidate -> candidate.id().equals(item.taskId())).findFirst();
            if (task.isEmpty()) continue;
            Version rule = active(actor, item.intent(), task.get().definitionVersion());
            if (rule == null || rule.spec().clarification().isBlank()) continue;
            outcomes.set(i, new Outcome(item.intent(), item.relation(), item.taskId(), item.status(), item.route(), item.skillHint(),
                    item.slots(), item.missing(), item.invalid(), rule.spec().clarification(), item.reason(),
                    item.dependsOn(), item.assessment(), item.ir(), item.workflowSkill(), item.slotProvenance()));
            changed = true;
        }
        return changed ? analysis.withResult(outcomes, analysis.state(), new ResponseComposer().reply(outcomes)) : analysis;
    }

    private Version active(Actor actor, String intent, int definitionVersion) {
        return db.sql("""
                SELECT version,status,spec::text FROM intent.predefined_rule
                WHERE tenant_id=? AND intent_key=? AND definition_version=? AND status='PUBLISHED'
                """).params(actor.tenant(), intent, definitionVersion).query((rs, row) ->
                new Version(rs.getInt(1), rs.getString(2), decode(rs.getString(3)))).optional().orElse(null);
    }

    private com.example.enterpriseagent.intent.IntentModel.Definition definition(String intent, int version) {
        if (intent == null || !intent.matches("[A-Z][A-Z0-9_]{0,39}\\.[A-Z][A-Z0-9_]{0,39}") || version < 1) {
            throw new Failure(400, "意图或定义版本无效");
        }
        var result = engine.definition(intent, version);
        if (result == null || !"DIRECT".equals(result.route()) || !"NORMAL".equals(result.risk())) {
            throw new Failure(400, "该意图定义未开放预定义规则");
        }
        return result;
    }

    private Spec decode(String value) {
        try { return json.readValue(value, Spec.class); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("无法读取规则", ex); }
    }

    public record Admission(int version, String blocked) {}
}
