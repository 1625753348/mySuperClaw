package com.example.enterpriseagent.infrastructure.memory;

import com.example.enterpriseagent.memory.episode.EpisodicMemory;
import com.example.enterpriseagent.memory.profile.ProfileMemory;
import com.example.enterpriseagent.memory.semantic.SemanticMemory;
import com.example.enterpriseagent.memory.store.MemoryAdministration;
import com.example.enterpriseagent.memory.store.MemoryVectorIndex;
import com.example.enterpriseagent.runtime.RunModel.Run;
import com.example.enterpriseagent.toolgateway.registry.ToolContracts;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionOperations;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** PostgreSQL 保存权威正文。索引作业只传递引用，读取时再检查文档版本。 */
public final class MemoryLoop implements MemoryAdministration {
    private static final String POLICY = "tenant.read";
    private static final Map<String, List<String>> PREFERENCES = Map.of(
            "locale", List.of("zh-CN", "en-US"), "reportStyle", List.of("brief", "detailed"));
    private final JdbcClient db;
    private final TransactionOperations tx;
    private final ObjectMapper json;
    private final ProfileMemory profiles;
    private final EpisodicMemory episodes;
    private final SemanticMemory knowledge;
    private final MemoryVectorIndex index;
    private final Function<String, List<Float>> embed;
    private final double threshold;

    public MemoryLoop(JdbcClient db, TransactionOperations tx, ObjectMapper json, ProfileMemory profiles,
                      EpisodicMemory episodes, SemanticMemory knowledge, MemoryVectorIndex index,
                      Function<String, List<Float>> embed, double threshold) {
        if (!Double.isFinite(threshold) || threshold < -1 || threshold > 1) {
            throw new IllegalArgumentException("记忆召回分数阈值无效");
        }
        this.db = db;
        this.tx = tx;
        this.json = json;
        this.profiles = profiles;
        this.episodes = episodes;
        this.knowledge = knowledge;
        this.index = index;
        this.embed = embed;
        this.threshold = threshold;
    }

    @Override
    public void profile(String tenant, String user, String key, String value, long revision) {
        if (value == null || revision < 1 || !PREFERENCES.getOrDefault(key, List.of()).contains(value)) {
            throw new Failure(400, "档案键、值或修订号无效");
        }
        try {
            profiles.write(new ProfileMemory.Item(tenant, user, key, json.getNodeFactory().textNode(value).toString(),
                    "USER_CONFIRMED", null, revision, null));
        } catch (IllegalStateException conflict) {
            throw new Failure(409, "档案已有相同或更高的修订号");
        }
    }

    public Map<String, String> profile(String tenant, String user) {
        var values = new LinkedHashMap<String, String>();
        for (String key : PREFERENCES.keySet()) {
            profiles.read(new ProfileMemory.Key(tenant, user, key)).ifPresent(item -> {
                try {
                    var value = json.readTree(item.valueJson());
                    if (value.isTextual() && PREFERENCES.get(key).contains(value.asText())) values.put(key, value.asText());
                } catch (JsonProcessingException invalid) {
                    // 存量档案可能使用不同的数据格式，本次只加载已登记偏好。
                }
            });
        }
        return Map.copyOf(values);
    }

    @Override
    public void publish(String tenant, UUID document, int version, String sourceKey, List<String> chunks) {
        if (sourceKey == null || !sourceKey.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}") || version < 1
                || chunks == null || chunks.isEmpty() || chunks.size() > 12
                || chunks.stream().anyMatch(chunk -> chunk == null || chunk.isBlank() || chunk.length() > 6_000)) {
            throw new Failure(400, "知识来源、版本或分块无效");
        }
        try {
            tx.executeWithoutResult(ignored -> {
                var entries = new ArrayList<SemanticMemory.Chunk>();
                String sourceRef = "pg:knowledge/" + document + "/" + version;
                for (int i = 0; i < chunks.size(); i++) {
                    entries.add(new SemanticMemory.Chunk(i, sourceRef + "/" + i, ToolContracts.digest(chunks.get(i))));
                }
                var metadata = new SemanticMemory.Version(tenant, document, version, sourceRef,
                        ToolContracts.digest(json.valueToTree(chunks).toString()));
                knowledge.write(new SemanticMemory.Publication(metadata, "business", sourceKey, POLICY, entries));
                for (int i = 0; i < chunks.size(); i++) {
                    db.sql("INSERT INTO memory.knowledge_chunk_body VALUES(?,?,?,?,?)")
                            .params(tenant, document, version, i, chunks.get(i)).update();
                    enqueue(tenant, MemoryVectorIndex.Kind.SEMANTIC, document + "/" + version + "/" + i);
                }
            });
        } catch (IllegalStateException | org.springframework.dao.DataIntegrityViolationException conflict) {
            throw new Failure(409, "知识版本或来源与当前记录冲突");
        }
    }

    /** 与 Run 成功在同一事务保存案例和索引作业，仅保留结构化场景与执行模式。 */
    public void capture(Run run) {
        String scenario = db.sql("""
                SELECT d.domain || '.' || d.action FROM intent.task t JOIN intent.intent_record i
                  ON i.tenant_id=t.tenant_id AND i.id=t.intent_id
                JOIN intent.definition d ON d.tenant_id=i.tenant_id AND d.id=i.definition_id
                WHERE t.tenant_id=? AND t.id=?
                """).params(run.tenant(), run.taskId()).query(String.class).optional().orElse("");
        if (!scenario.matches("[A-Z][A-Z0-9_]{0,39}\\.[A-Z][A-Z0-9_]{0,39}")) return;
        int inserted = db.sql("""
                INSERT INTO memory.task_episode(tenant_id,id,source_run_id,owner_user_id,scope,scenario_key,
                    status,revision,episode_ref,expires_at,summary_text)
                VALUES(?,?,?,?,'USER',?,'ACTIVE',1,?,now()+interval '90 days',?)
                ON CONFLICT(tenant_id,source_run_id) DO NOTHING
                """).params(run.tenant(), run.id(), run.id(), run.userId(), scenario, "pg:episode/" + run.id(),
                scenario + " / " + run.route() + " / SUCCEEDED").update();
        if (inserted == 1) enqueue(run.tenant(), MemoryVectorIndex.Kind.EPISODE, run.id().toString());
    }

    public List<String> recall(String tenant, String user, String scenario) {
        return episodes.search(tenant, user, false, embed.apply(scenario), 20).stream()
                .filter(item -> scenario.equals(item.scenarioKey())).limit(3).map(item -> item.id().toString()).toList();
    }

    public List<String> retrieve(String tenant, String question) {
        return index.search(tenant, MemoryVectorIndex.Kind.SEMANTIC, embed.apply(question), 20).stream()
                .filter(hit -> hit.score() >= threshold)
                .map(MemoryVectorIndex.Hit::sourceKey)
                .filter(key -> chunk(tenant, key) != null).distinct().limit(3).toList();
    }

    public Map<String, Object> evidence(String tenant, List<String> refs) {
        var sources = refs.stream().limit(3).map(key -> chunk(tenant, key)).filter(java.util.Objects::nonNull).toList();
        if (sources.isEmpty()) return Map.of();
        String answer = String.join("\n\n", sources.stream().map(Chunk::excerpt).toList());
        return Map.of("answer", answer, "sources", sources);
    }

    private Chunk chunk(String tenant, String key) {
        String[] parts = key.split("/", -1);
        if (parts.length != 3) return null;
        UUID doc;
        int version;
        int ordinal;
        try {
            doc = UUID.fromString(parts[0]);
            version = Integer.parseInt(parts[1]);
            ordinal = Integer.parseInt(parts[2]);
        } catch (IllegalArgumentException invalid) { return null; }
        return db.sql("""
                SELECT d.source_key,b.body FROM memory.knowledge_chunk_body b
                JOIN memory.knowledge_document d ON d.tenant_id=b.tenant_id AND d.id=b.document_id
                JOIN memory.knowledge_version v ON v.tenant_id=b.tenant_id AND v.document_id=b.document_id
                    AND v.version=b.document_version
                WHERE b.tenant_id=? AND b.document_id=? AND b.document_version=? AND b.ordinal=?
                  AND d.access_policy_ref=? AND v.status='ACTIVE'
                """).params(tenant, doc, version, ordinal, POLICY)
                .query((rs, row) -> new Chunk(key, rs.getString(1), version, ordinal, rs.getString(2))).optional().orElse(null);
    }

    private void enqueue(String tenant, MemoryVectorIndex.Kind kind, String source) {
        db.sql("INSERT INTO memory.index_job(tenant_id,kind,source_key) VALUES(?,?,?) ON CONFLICT DO NOTHING")
                .params(tenant, kind.name(), source).update();
    }

    public void indexOne() {
        UUID lease = UUID.randomUUID();
        var item = db.sql("""
                WITH due AS (
                    SELECT tenant_id,kind,source_key FROM memory.index_job
                    WHERE indexed_at IS NULL AND failed_at IS NULL AND next_at<=now()
                      AND (lease_until IS NULL OR lease_until<=now())
                    ORDER BY next_at,tenant_id,kind,source_key LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE memory.index_job j SET lease_id=?,lease_until=now()+interval '45 seconds',attempts=attempts+1
                FROM due WHERE (j.tenant_id,j.kind,j.source_key)=(due.tenant_id,due.kind,due.source_key)
                RETURNING j.tenant_id,j.kind,j.source_key
                """).param(lease).query((rs, row) -> new Job(rs.getString(1),
                MemoryVectorIndex.Kind.valueOf(rs.getString(2)), rs.getString(3))).optional();
        if (item.isEmpty()) return;
        Job job = item.get();
        try {
            String input;
            if (job.kind() == MemoryVectorIndex.Kind.EPISODE) {
                input = db.sql("""
                        SELECT scenario_key FROM memory.task_episode WHERE tenant_id=? AND id=? AND status='ACTIVE'
                          AND (expires_at IS NULL OR expires_at>now())
                        """).params(job.tenant(), UUID.fromString(job.source())).query(String.class).optional().orElse(null);
            } else {
                var chunk = chunk(job.tenant(), job.source());
                input = chunk == null ? null : chunk.excerpt();
            }
            if (input != null) index.upsert(job.tenant(), job.kind(), job.source(), embed.apply(input));
            db.sql("UPDATE memory.index_job SET indexed_at=now(),lease_id=NULL,lease_until=NULL WHERE lease_id=?")
                    .param(lease).update();
        } catch (RuntimeException failure) {
            db.sql("""
                    UPDATE memory.index_job SET lease_id=NULL,lease_until=NULL,next_at=now()+attempts*interval '5 seconds',
                        failed_at=CASE WHEN attempts>=6 THEN now() END WHERE lease_id=?
                    """).param(lease).update();
        }
    }

    private record Job(String tenant, MemoryVectorIndex.Kind kind, String source) {}
    private record Chunk(String id, String title, int version, int ordinal, String excerpt) {}
}
