package com.example.enterpriseagent.infrastructure.persistence;

import com.example.enterpriseagent.memory.conversation.ConversationMemory;
import com.example.enterpriseagent.memory.episode.EpisodicMemory;
import com.example.enterpriseagent.memory.profile.ProfileMemory;
import com.example.enterpriseagent.memory.semantic.SemanticMemory;
import com.example.enterpriseagent.memory.working.WorkingMemory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

/** 使用 MyBatis-Plus 的 SqlSessionFactory 执行 SQL，复合键显式绑定租户。 */
@Mapper
public interface MemoryMapper {

    @Select("""
            SELECT tenant_id, user_id, item_key, value::text, source_type, source_ref, revision, expires_at
            FROM memory.profile_item
            WHERE tenant_id=#{tenantId} AND user_id=#{userId} AND item_key=#{itemKey}
              AND (expires_at IS NULL OR expires_at>now())
            """)
    ProfileMemory.Item profile(ProfileMemory.Key key);

    @Insert("""
            INSERT INTO memory.profile_item
                (tenant_id,user_id,item_key,value,source_type,source_ref,revision,expires_at)
            VALUES (#{tenantId},#{userId},#{itemKey},CAST(#{valueJson} AS jsonb),
                    #{sourceType},#{sourceRef},#{revision},#{expiresAt})
            ON CONFLICT (tenant_id,user_id,item_key) DO UPDATE
            SET value=excluded.value, source_type=excluded.source_type, source_ref=excluded.source_ref,
                revision=excluded.revision, expires_at=excluded.expires_at, updated_at=now()
            WHERE profile_item.revision < excluded.revision
            """)
    int saveProfile(ProfileMemory.Item item);

    @Select("""
            SELECT s.tenant_id,s.conversation_id,s.version,s.history_revision,s.covered_through_seq,
                   s.source_digest,s.summarizer_version,s.summary_ref
            FROM memory.conversation_summary s JOIN intent.conversation c
              USING (tenant_id,history_revision)
            WHERE c.id=s.conversation_id AND c.tenant_id=#{tenantId}
              AND c.id=#{conversationId} AND c.owner_user_id=#{userId}
            ORDER BY s.covered_through_seq DESC LIMIT 1
            """)
    ConversationMemory.Summary summary(ConversationMemory.Key key);

    @Select("""
            SELECT seq,role,body_ref FROM (
                SELECT m.seq,m.role,m.body_ref
                FROM intent.message m JOIN intent.conversation c
                  ON c.tenant_id=m.tenant_id AND c.id=m.conversation_id
                WHERE c.tenant_id=#{key.tenantId} AND c.id=#{key.conversationId}
                  AND c.owner_user_id=#{key.userId} AND m.redacted_at IS NULL AND m.seq>#{after}
                ORDER BY m.seq DESC LIMIT #{key.limit}
            ) recent ORDER BY seq
            """)
    List<ConversationMemory.Message> window(@Param("key") ConversationMemory.Key key, @Param("after") long after);

    @Select("""
            SELECT last_message_seq FROM intent.conversation
            WHERE tenant_id=#{tenantId} AND id=#{conversationId} AND history_revision=#{historyRevision}
            FOR UPDATE
            """)
    Long lockConversation(ConversationMemory.Summary summary);

    @Insert("""
            INSERT INTO memory.conversation_summary
                (tenant_id,conversation_id,version,history_revision,covered_through_seq,source_digest,
                 summarizer_version,summary_ref)
            SELECT #{tenantId},#{conversationId},#{version},#{historyRevision},#{coveredThroughSeq},
                   #{sourceDigest},#{summarizerVersion},#{summaryRef}
            WHERE NOT EXISTS (
                SELECT 1 FROM memory.conversation_summary
                WHERE tenant_id=#{tenantId} AND conversation_id=#{conversationId}
                  AND (version>=#{version} OR
                       (history_revision=#{historyRevision} AND covered_through_seq>=#{coveredThroughSeq}))
            )
            """)
    int appendSummary(ConversationMemory.Summary summary);

    @Select("""
            SELECT tenant_id,id,source_run_id,owner_user_id,scope,scenario_key,status,revision,episode_ref,expires_at
            FROM memory.task_episode
            WHERE tenant_id=#{tenantId} AND id=#{id} AND status='ACTIVE'
              AND ((scope='USER' AND owner_user_id=#{userId}) OR (scope='TENANT' AND #{allowTenantScope}))
              AND (expires_at IS NULL OR expires_at>now())
            """)
    EpisodicMemory.Episode episode(EpisodicMemory.Key key);

    @Insert("""
            INSERT INTO memory.task_episode
                (tenant_id,id,source_run_id,owner_user_id,scope,scenario_key,status,revision,episode_ref,expires_at)
            SELECT #{tenantId},#{id},#{sourceRunId},#{ownerUserId},#{scope},#{scenarioKey},#{status},
                   #{revision},#{episodeRef},#{expiresAt}
            WHERE EXISTS (SELECT 1 FROM runtime.run WHERE tenant_id=#{tenantId} AND id=#{sourceRunId}
                          AND user_id=#{ownerUserId} AND status='SUCCEEDED')
               OR EXISTS (SELECT 1 FROM memory.task_episode WHERE tenant_id=#{tenantId}
                          AND id=#{id} AND source_run_id=#{sourceRunId} AND owner_user_id=#{ownerUserId})
            ON CONFLICT (tenant_id,id) DO UPDATE
            SET scope=excluded.scope, scenario_key=excluded.scenario_key, status=excluded.status,
                revision=excluded.revision, episode_ref=excluded.episode_ref, expires_at=excluded.expires_at
            WHERE task_episode.revision<excluded.revision AND task_episode.source_run_id=excluded.source_run_id
              AND task_episode.owner_user_id=excluded.owner_user_id
            """)
    int saveEpisode(EpisodicMemory.Episode episode);

    @Select("""
            SELECT v.tenant_id,v.document_id,v.version,v.source_ref,v.content_digest
            FROM memory.knowledge_version v JOIN memory.knowledge_document d
              ON d.tenant_id=v.tenant_id AND d.id=v.document_id
            WHERE d.tenant_id=#{tenantId} AND d.id=#{documentId}
              AND d.access_policy_ref=#{accessPolicyRef} AND v.status='ACTIVE'
            """)
    SemanticMemory.Version knowledge(SemanticMemory.Key key);

    @Insert("""
            INSERT INTO memory.knowledge_document (tenant_id,id,collection_key,source_key,access_policy_ref)
            VALUES (#{version.tenantId},#{version.documentId},#{collectionKey},#{sourceKey},#{accessPolicyRef})
            ON CONFLICT (tenant_id,id) DO UPDATE SET access_policy_ref=excluded.access_policy_ref
            WHERE knowledge_document.collection_key=excluded.collection_key AND knowledge_document.source_key=excluded.source_key
            """)
    int lockDocument(SemanticMemory.Publication publication);

    @Select("""
            SELECT coalesce(max(version),0) FROM memory.knowledge_version
            WHERE tenant_id=#{tenantId} AND document_id=#{documentId}
            """)
    int lastKnowledgeVersion(SemanticMemory.Version version);

    @Update("""
            UPDATE memory.knowledge_version SET status='RETIRED'
            WHERE tenant_id=#{tenantId} AND document_id=#{documentId} AND status='ACTIVE'
            """)
    int retireKnowledge(SemanticMemory.Version version);

    @Insert("""
            INSERT INTO memory.knowledge_version
                (tenant_id,document_id,version,status,source_ref,content_digest,published_at)
            VALUES (#{tenantId},#{documentId},#{version},'ACTIVE',#{sourceRef},#{contentDigest},now())
            """)
    int publishKnowledge(SemanticMemory.Version version);

    @Insert("""
            INSERT INTO memory.knowledge_chunk
                (tenant_id,document_id,document_version,ordinal,chunk_ref,content_digest)
            VALUES (#{version.tenantId},#{version.documentId},#{version.version},#{chunk.ordinal},
                    #{chunk.chunkRef},#{chunk.contentDigest})
            """)
    int appendChunk(@Param("version") SemanticMemory.Version version, @Param("chunk") SemanticMemory.Chunk chunk);

    record CheckpointRow(String tenantId, UUID runId, long seq, int planVersion, UUID afterStepId,
                         String graphThreadKey, int formatVersion, String graphStateJson) {}

    @Select("""
            SELECT c.tenant_id,c.run_id,c.seq,c.plan_version,c.after_step_id,
                   c.graph_thread_key,c.format_version,c.graph_state::text
            FROM runtime.checkpoint c JOIN runtime.run r ON r.tenant_id=c.tenant_id AND r.id=c.run_id
            WHERE c.tenant_id=#{tenantId} AND c.run_id=#{runId} AND c.plan_version=r.active_plan_version
              AND c.graph_thread_key=#{graphThreadKey} AND c.format_version=#{formatVersion}
            ORDER BY c.seq DESC LIMIT 1
            """)
    CheckpointRow latestCheckpoint(WorkingMemory.Key key);

    @Select("""
            SELECT binding_key,artifact_id FROM runtime.checkpoint_artifact
            WHERE tenant_id=#{tenantId} AND run_id=#{runId} AND checkpoint_seq=#{seq} ORDER BY binding_key
            """)
    List<WorkingMemory.Binding> bindings(CheckpointRow row);

    @Select("""
            SELECT active_plan_version FROM runtime.run
            WHERE tenant_id=#{tenantId} AND id=#{runId} FOR UPDATE
            """)
    Integer lockRun(WorkingMemory.Snapshot snapshot);

    @Insert("""
            INSERT INTO runtime.checkpoint
                (tenant_id,run_id,seq,plan_version,after_step_id,graph_thread_key,format_version,graph_state)
            SELECT #{tenantId},#{runId},#{seq},#{planVersion},#{afterStepId},
                   #{graphThreadKey},#{formatVersion},CAST(#{graphStateJson} AS jsonb)
            WHERE NOT EXISTS (SELECT 1 FROM runtime.checkpoint
                WHERE tenant_id=#{tenantId} AND run_id=#{runId} AND seq>=#{seq})
            """)
    int appendCheckpoint(WorkingMemory.Snapshot snapshot);

    @Insert("""
            INSERT INTO runtime.checkpoint_artifact (tenant_id,run_id,checkpoint_seq,artifact_id,binding_key)
            VALUES (#{snapshot.tenantId},#{snapshot.runId},#{snapshot.seq},#{binding.artifactId},#{binding.bindingKey})
            """)
    int bindArtifact(@Param("snapshot") WorkingMemory.Snapshot snapshot, @Param("binding") WorkingMemory.Binding binding);
}
