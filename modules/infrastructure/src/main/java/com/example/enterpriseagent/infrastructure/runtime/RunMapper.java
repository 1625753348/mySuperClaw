package com.example.enterpriseagent.infrastructure.runtime;

import com.example.enterpriseagent.runtime.RunStore;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

import static com.example.enterpriseagent.runtime.RunModel.*;

@Mapper
public interface RunMapper extends RunStore {
    String RUN = """
            SELECT tenant_id AS tenant,id,task_id,user_id,agent_id,trace_id,route,active_plan_version,status,last_event_seq,
                   gate_policy_version,max_iterations,deadline_at,terminal_reason,result_ref,
                   cancel_requested_at IS NOT NULL AS cancelled, deadline_at<=now() AS expired,
                   coalesce((budget_used->>'plannerCalls')::int,0) AS planner_calls,graph_version
            FROM runtime.run WHERE tenant_id=#{tenant} AND id=#{id}
              AND graph_version IN ('sales-report-v1','published-workflow-v1','general-plan-v1','subagent-v1')
            """;
    String STEP = """
            SELECT id,node_key,kind,spec::text,status,operation_key,tool_execution_id,observation_ref::uuid AS observation_id,
                   current_attempt,lease_epoch,lease_owner FROM runtime.step
            WHERE tenant_id=#{tenant} AND run_id=#{run} AND plan_version=#{plan}
            """;

    @Insert("""
            INSERT INTO runtime.run_submission(tenant_id,user_id,agent_id,request_key,run_id,request_hash,roles)
            VALUES(#{tenant},#{user},#{agent},#{key},#{run},#{hash},CAST(#{roles} AS jsonb))
            ON CONFLICT(tenant_id,user_id,agent_id,request_key) DO NOTHING
            """)
    int reserveSubmission(Admission data);

    @Select("""
            SELECT run_id,request_hash,roles::text FROM runtime.run_submission
            WHERE tenant_id=#{tenant} AND user_id=#{user} AND agent_id=#{agent} AND request_key=#{key}
            """)
    Receipt receipt(@Param("tenant") String tenant, @Param("user") String user, @Param("agent") String agent, @Param("key") String key);

    /** 结构化入口为既有意图、任务和准入外键提供真实绑定。 */
    @Insert("""
            WITH definition AS (
                INSERT INTO intent.definition(tenant_id,id,domain,action,version,status,parameter_schema)
                VALUES(#{tenant},#{definition},'RUNTIME',CASE WHEN #{delegated} THEN 'SUBAGENT' ELSE 'SALES_REPORT' END,
                       1,'PUBLISHED','{"type":"object"}')
                ON CONFLICT(tenant_id,domain,action,version) DO UPDATE SET version=excluded.version RETURNING id
            ), conversation AS (
                INSERT INTO intent.conversation(tenant_id,id,owner_user_id) VALUES(#{tenant},#{conversation},#{user}) RETURNING id
            ), turn_entry AS (
                INSERT INTO intent.turn(tenant_id,id,conversation_id,actor_user_id,client_key,request_hash,status)
                SELECT #{tenant},#{turn},id,#{user},#{key},#{hash},'COMPLETED' FROM conversation RETURNING id,conversation_id
            ), intent_entry AS (
                INSERT INTO intent.intent_record(tenant_id,id,conversation_id,origin_turn_id,definition_id)
                SELECT #{tenant},#{intent},t.conversation_id,t.id,d.id FROM turn_entry t CROSS JOIN definition d
                RETURNING id,conversation_id
            ), candidate AS (
                INSERT INTO intent.candidate(tenant_id,id,conversation_id,turn_id,ordinal,draft,relation,status)
                SELECT #{tenant},#{candidate},conversation_id,id,0,'{"source":"structured-runtime"}','NEW_TASK','APPLIED'
                FROM turn_entry RETURNING id
            ), task AS (
                INSERT INTO intent.task(tenant_id,id,conversation_id,intent_id,status,input_version)
                SELECT #{tenant},#{task},conversation_id,id,'EXECUTING',1 FROM intent_entry RETURNING id,conversation_id
            ), input AS (
                INSERT INTO intent.task_input(tenant_id,task_id,version,conversation_id,source_candidate_id,slots,validated_ir)
                SELECT #{tenant},t.id,1,t.conversation_id,c.id,CAST(#{request} AS jsonb),
                       CASE WHEN #{delegated} THEN CAST(#{request} AS jsonb)
                            ELSE '{"domain":"RUNTIME","action":"SALES_REPORT","source":"structured-runtime"}'::jsonb END
                FROM task t CROSS JOIN candidate c RETURNING task_id
            ), decision AS (
                INSERT INTO intent.route_decision(tenant_id,id,task_id,input_version,policy_version,verdict,route)
                SELECT #{tenant},#{decision},task_id,1,CASE WHEN #{delegated} THEN 'subagent-intake-v1' ELSE 'sales-intake-v1' END,
                       'ALLOW',#{route} FROM input RETURNING id,task_id
            )
            INSERT INTO runtime.run(tenant_id,id,task_id,input_version,route_decision_id,route,user_id,agent_id,trace_id,
                graph_version,active_plan_version,status,gate_policy_version,max_iterations,budget_limits,deadline_at)
            SELECT #{tenant},#{run},task_id,1,id,#{route},#{user},#{agent},#{trace},
                CASE WHEN #{delegated} THEN 'subagent-v1' ELSE 'sales-report-v1' END,
                1,'QUEUED',#{gate},#{iterations},'{"plannerCalls":4,"nodesPerPlan":6}',
                now()+#{deadlineSeconds}*interval '1 second' FROM decision
            """)
    void insertAdmission(Admission data);

    @Insert("""
            WITH ready AS (
                UPDATE intent.task t SET status='EXECUTING',revision=t.revision+1,updated_at=now()
                FROM intent.task_input ti
                WHERE t.tenant_id=#{tenant} AND t.id=#{task} AND t.status='READY' AND t.input_version=#{inputVersion}
                    AND ti.tenant_id=t.tenant_id AND ti.task_id=t.id AND ti.version=t.input_version
                    AND ti.workflow_skill_version_id=#{skillVersion}
                RETURNING t.id
            )
            INSERT INTO runtime.run(tenant_id,id,task_id,input_version,route_decision_id,route,user_id,agent_id,
                trace_id,graph_version,active_plan_version,status,deadline_at)
            SELECT #{tenant},#{run},t.id,#{inputVersion},r.id,'WORKFLOW',#{user},#{agent},#{trace},
                'published-workflow-v1',1,'QUEUED',now()+interval '30 minutes'
            FROM ready t JOIN intent.route_decision r ON r.tenant_id=#{tenant} AND r.task_id=t.id
              AND r.input_version=#{inputVersion} AND r.verdict='ALLOW' AND r.route='WORKFLOW'
            """)
    int insertFixedAdmission(FixedAdmission data);

    @Insert("""
            WITH ready AS (
                UPDATE intent.task t SET status='EXECUTING',revision=t.revision+1,updated_at=now()
                FROM intent.task_input ti, intent.route_decision d
                WHERE t.tenant_id=#{tenant} AND t.id=#{task} AND t.status='READY' AND t.input_version=#{inputVersion}
                  AND ti.tenant_id=t.tenant_id AND ti.task_id=t.id AND ti.version=t.input_version
                  AND d.tenant_id=t.tenant_id AND d.task_id=t.id AND d.input_version=t.input_version
                  AND d.verdict='ALLOW' AND d.route='AGENT'
                RETURNING t.id,d.id AS decision_id
            )
            INSERT INTO runtime.run(tenant_id,id,task_id,input_version,route_decision_id,route,user_id,agent_id,
                trace_id,graph_version,active_plan_version,status,gate_policy_version,max_iterations,budget_limits,deadline_at)
            SELECT #{tenant},#{run},id,#{inputVersion},decision_id,'AGENT',#{user},#{agent},#{trace},
                'general-plan-v1',1,'QUEUED','general-hard-gate-v1',#{iterations},
                CAST(#{policy} AS jsonb),now()+interval '30 minutes'
            FROM ready
            """)
    int insertGeneralAdmission(GeneralAdmission data);

    @Select("""
            SELECT child_run_id AS id,attempt_no AS attempt FROM runtime.child_run
            WHERE tenant_id=#{tenant} AND parent_run_id=#{run} AND parent_step_id=#{step}
            ORDER BY attempt_no DESC LIMIT 1
            """)
    ChildAttempt latestChild(@Param("tenant") String tenant, @Param("run") UUID run, @Param("step") UUID step);

    @Insert("""
            INSERT INTO runtime.child_run(tenant_id,parent_run_id,parent_step_id,child_run_id,subagent_key,attempt_no)
            VALUES(#{tenant},#{run},#{step},#{child},#{name},#{attempt})
            """)
    void insertChildRun(@Param("tenant") String tenant, @Param("run") UUID run, @Param("step") UUID step,
                        @Param("child") UUID child, @Param("name") String name, @Param("attempt") int attempt);

    @Select("""
            SELECT error_code FROM runtime.step_attempt
            WHERE tenant_id=#{tenant} AND step_id=#{step} ORDER BY attempt_no DESC LIMIT 1
            """)
    String latestStepReason(@Param("tenant") String tenant, @Param("step") UUID step);

    @Select("""
            SELECT EXISTS(SELECT 1 FROM gateway.tool_execution e JOIN gateway.tool_approval a
              ON a.tenant_id=e.tenant_id AND a.execution_id=e.id WHERE e.tenant_id=#{tenant} AND e.run_id=#{child})
            """)
    boolean childHadApproval(@Param("tenant") String tenant, @Param("child") UUID child);

    @Select("SELECT budget_limits::text FROM runtime.run WHERE tenant_id=#{tenant} AND id=#{run}")
    String generalPolicy(@Param("tenant") String tenant, @Param("run") UUID run);

    @Select("""
            SELECT g.check_key AS key, coalesce(b.body->'failures'->>g.check_key,'REQUIRED_VALUE_MISSING') AS reason
            FROM runtime.gate_check g LEFT JOIN runtime.artifact_body b
              ON b.tenant_id=g.tenant_id AND b.run_id=g.run_id AND b.artifact_id::text=g.evidence_ref
            WHERE g.tenant_id=#{tenant} AND g.run_id=#{run} AND g.plan_version=#{plan} AND g.verdict<>'PASS'
            ORDER BY g.check_key
            """)
    List<GateFailure> generalGateFailures(@Param("tenant") String tenant, @Param("run") UUID run, @Param("plan") int plan);

    @Select(RUN + " AND user_id=#{user} AND agent_id=#{agent}")
    Run owned(@Param("tenant") String tenant, @Param("user") String user, @Param("agent") String agent, @Param("id") UUID id);

    @Select(RUN + " FOR UPDATE")
    Run lock(@Param("tenant") String tenant, @Param("id") UUID id);

    @Select("SELECT run_id,request_hash,roles::text FROM runtime.run_submission WHERE tenant_id=#{tenant} AND run_id=#{id}")
    Receipt submission(@Param("tenant") String tenant, @Param("id") UUID id);

    @Select("SELECT slots::text FROM intent.task_input WHERE tenant_id=#{tenant} AND task_id=#{taskId} AND version=1")
    String input(@Param("tenant") String tenant, @Param("taskId") UUID taskId);

    @Select("""
            SELECT i.validated_ir::text FROM runtime.run r
            JOIN intent.task_input i ON i.tenant_id=r.tenant_id AND i.task_id=r.task_id AND i.version=r.input_version
            WHERE r.tenant_id=#{tenant} AND r.id=#{run}
            """)
    String fixedInput(@Param("tenant") String tenant, @Param("run") UUID run);

    @Select("""
            SELECT r.tenant_id AS tenant,r.id FROM runtime.run r
            WHERE r.graph_version IN ('sales-report-v1','published-workflow-v1','general-plan-v1','subagent-v1')
              AND r.finished_at IS NULL AND r.status<>'NEEDS_RECONCILIATION'
              AND ((r.deadline_at<=now() AND r.cancel_requested_at IS NULL)
                OR EXISTS (SELECT 1 FROM runtime.plan_proposal p WHERE p.tenant_id=r.tenant_id AND p.run_id=r.id
                    AND p.status='REQUESTED' AND p.next_at<=now() AND (p.lease_until IS NULL OR p.lease_until<=now()))
                OR EXISTS (SELECT 1 FROM runtime.step s WHERE s.tenant_id=r.tenant_id AND s.run_id=r.id
                    AND s.plan_version=r.active_plan_version AND s.next_at<=now()
                    AND s.status IN ('READY','RETRY_WAIT','RUNNING','WAITING_APPROVAL','WAITING_EXTERNAL')
                    AND (s.lease_until IS NULL OR s.lease_until<=now())))
            ORDER BY r.created_at,r.id LIMIT 1 FOR UPDATE OF r SKIP LOCKED
            """)
    CandidateRun lockDueRun();

    @Insert("""
            INSERT INTO runtime.plan(tenant_id,run_id,version,origin,proposal_id,plan_hash,workflow_skill_version_id)
            VALUES(#{tenant},#{run},#{version},#{origin},#{proposal},#{hash},#{skillVersion})
            """)
    void insertPlan(PlanVersion row);

    @Insert("""
            INSERT INTO runtime.step(tenant_id,id,run_id,plan_version,node_key,kind,spec,status,operation_key)
            VALUES(#{tenant},#{id},#{run},#{plan},#{key},#{kind},CAST(#{spec} AS jsonb),#{status},#{operationKey})
            """)
    void insertStep(StepRow row);

    @Insert("""
            INSERT INTO runtime.step_dependency(tenant_id,run_id,plan_version,step_id,depends_on_step_id)
            VALUES(#{tenant},#{run},#{plan},#{step},#{parent})
            """)
    void insertDependency(Dependency row);

    @Select(STEP + " ORDER BY node_key")
    List<Step> steps(@Param("tenant") String tenant, @Param("run") UUID run, @Param("plan") int plan);

    @Select(STEP + """
              AND status IN ('READY','RETRY_WAIT','RUNNING','WAITING_APPROVAL','WAITING_EXTERNAL')
              AND (NOT #{cancelled} OR status IN ('RUNNING','WAITING_APPROVAL','WAITING_EXTERNAL'))
              AND next_at<=now() AND (lease_until IS NULL OR lease_until<=now())
              ORDER BY next_at,id LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    Step dueStep(@Param("tenant") String tenant, @Param("run") UUID run, @Param("plan") int plan,
                 @Param("cancelled") boolean cancelled);

    @Update("""
            UPDATE runtime.step SET status='RUNNING',current_attempt=current_attempt+1,lease_epoch=lease_epoch+1,
              lease_owner=#{worker},lease_until=now()+#{leaseSeconds}*interval '1 second',updated_at=now()
            WHERE tenant_id=#{tenant} AND id=#{id} AND (lease_until IS NULL OR lease_until<=now())
            """)
    int claimStep(@Param("tenant") String tenant, @Param("id") UUID id, @Param("worker") String worker,
                  @Param("leaseSeconds") int leaseSeconds);

    @Update("""
            UPDATE runtime.step_attempt SET status='LEASE_EXPIRED',finished_at=now()
            WHERE tenant_id=#{tenant} AND step_id=#{id} AND attempt_no=#{attempt} AND status='RUNNING'
            """)
    void abandonAttempt(@Param("tenant") String tenant, @Param("id") UUID id, @Param("attempt") int attempt);

    @Insert("""
            INSERT INTO runtime.step_attempt(tenant_id,step_id,attempt_no,lease_epoch,worker_id,status)
            SELECT tenant_id,id,current_attempt,lease_epoch,#{worker},'RUNNING'
            FROM runtime.step WHERE tenant_id=#{tenant} AND id=#{id}
            """)
    void startAttempt(@Param("tenant") String tenant, @Param("id") UUID id, @Param("worker") String worker);

    @Update("""
            UPDATE runtime.step SET status=#{state},tool_execution_id=coalesce(#{tool},tool_execution_id),
              observation_ref=coalesce(CAST(#{artifact} AS text),observation_ref),
              next_at=now()+#{delayMs}*interval '1 millisecond',lease_owner=NULL,lease_until=NULL,updated_at=now()
            WHERE tenant_id=#{tenant} AND id=#{step} AND lease_epoch=#{epoch} AND lease_owner=#{worker}
              AND status='RUNNING'
            """)
    int closeStep(StepChange change);

    @Update("""
            UPDATE runtime.step_attempt SET status=#{state},error_code=#{reason},finished_at=now()
            WHERE tenant_id=#{tenant} AND step_id=#{id} AND attempt_no=#{attempt} AND status='RUNNING'
            """)
    void finishAttempt(@Param("tenant") String tenant, @Param("id") UUID id, @Param("attempt") int attempt,
                       @Param("state") String state, @Param("reason") String reason);

    @Update("""
            UPDATE runtime.step s SET status='READY',next_at=now(),updated_at=now()
            WHERE s.tenant_id=#{tenant} AND s.run_id=#{run} AND s.plan_version=#{plan} AND s.status='PENDING'
              AND NOT EXISTS (SELECT 1 FROM runtime.step_dependency d JOIN runtime.step p
                  ON p.tenant_id=d.tenant_id AND p.id=d.depends_on_step_id
                  WHERE d.tenant_id=s.tenant_id AND d.run_id=s.run_id AND d.plan_version=s.plan_version
                    AND d.step_id=s.id AND p.status<>'SUCCEEDED')
            """)
    void readyDependents(@Param("tenant") String tenant, @Param("run") UUID run, @Param("plan") int plan);

    @Update("""
            UPDATE runtime.step SET status='CANCELLED',updated_at=now()
            WHERE tenant_id=#{tenant} AND run_id=#{run} AND plan_version=#{plan} AND status IN ('PENDING','READY','RETRY_WAIT')
            """)
    void cancelUnstarted(@Param("tenant") String tenant, @Param("run") UUID run, @Param("plan") int plan);

    @Update("""
            UPDATE runtime.step SET status='FAILED',updated_at=now()
            WHERE tenant_id=#{tenant} AND run_id=#{run} AND plan_version=#{plan} AND status='NEEDS_RECONCILIATION'
              AND lease_owner IS NULL
            """)
    int failUncertainSteps(@Param("tenant") String tenant, @Param("run") UUID run, @Param("plan") int plan);

    @Insert("""
            INSERT INTO runtime.plan_proposal(tenant_id,run_id,id,base_plan_version,kind,trigger_reason,planner_version,status)
            VALUES(#{tenant},#{run},#{id},#{base},#{kind},#{reason},#{plannerVersion},'REQUESTED')
            """)
    void insertProposal(ProposalRow row);

    @Select("""
            SELECT id,base_plan_version,kind,lease_epoch,lease_owner,attempts,trigger_reason FROM runtime.plan_proposal
            WHERE tenant_id=#{tenant} AND run_id=#{run} AND status='REQUESTED' AND next_at<=now()
              AND (lease_until IS NULL OR lease_until<=now()) LIMIT 1 FOR UPDATE SKIP LOCKED
            """)
    PlanProposal dueProposal(@Param("tenant") String tenant, @Param("run") UUID run);

    @Update("""
            UPDATE runtime.plan_proposal SET lease_owner=#{worker},lease_epoch=lease_epoch+1,attempts=attempts+1,
              planner_version=#{plannerVersion},
              lease_until=now()+#{leaseSeconds}*interval '1 second'
            WHERE tenant_id=#{tenant} AND run_id=#{run} AND id=#{proposal} AND status='REQUESTED'
              AND (lease_until IS NULL OR lease_until<=now())
            """)
    int claimProposal(@Param("tenant") String tenant, @Param("run") UUID run, @Param("proposal") UUID proposal,
                      @Param("worker") String worker, @Param("leaseSeconds") int leaseSeconds,
                      @Param("plannerVersion") String plannerVersion);

    @Update("""
            UPDATE runtime.run SET budget_used=jsonb_set(budget_used,'{plannerCalls}',
                to_jsonb(coalesce((budget_used->>'plannerCalls')::int,0)+1))
            WHERE tenant_id=#{tenant} AND id=#{run}
            """)
    void countPlannerCall(@Param("tenant") String tenant, @Param("run") UUID run);

    @Update("""
            UPDATE runtime.plan_proposal SET status=#{status},context_hash=#{contextHash},
              context_ref=CAST(#{contextArtifact} AS text),draft_ref=CAST(#{draftArtifact} AS text),
              validation_result=CAST(#{validation} AS jsonb),lease_owner=NULL,lease_until=NULL,settled_at=now()
            WHERE tenant_id=#{tenant} AND run_id=#{run} AND id=#{id} AND status='REQUESTED'
              AND lease_epoch=#{epoch} AND lease_owner=#{worker}
            """)
    int finishProposal(ProposalChange change);

    @Update("""
            UPDATE runtime.plan_proposal SET status='CANCELLED',lease_owner=NULL,lease_until=NULL,settled_at=now()
            WHERE tenant_id=#{tenant} AND run_id=#{run} AND status IN ('REQUESTED','GENERATED')
            """)
    void cancelProposals(@Param("tenant") String tenant, @Param("run") UUID run);

    @Update("""
            UPDATE runtime.run SET active_plan_version=#{plan},version=version+1
            WHERE tenant_id=#{tenant} AND id=#{run}
            """)
    void activatePlan(@Param("tenant") String tenant, @Param("run") UUID run, @Param("plan") int plan);

    @Insert("""
            INSERT INTO runtime.iteration(tenant_id,run_id,iteration_no,plan_version,gate_policy_version,context_hash,context_manifest,status)
            SELECT #{tenant},#{run},#{iteration},#{plan},gate_policy_version,#{hash},CAST(#{manifest} AS jsonb),'ACTIVE'
            FROM runtime.run WHERE tenant_id=#{tenant} AND id=#{run}
            """)
    void insertIteration(IterationRow row);

    @Update("""
            UPDATE runtime.iteration SET status=#{state},finished_at=now()
            WHERE tenant_id=#{tenant} AND run_id=#{run} AND iteration_no=#{iteration} AND status='ACTIVE'
            """)
    void finishIteration(@Param("tenant") String tenant, @Param("run") UUID run, @Param("iteration") int iteration,
                         @Param("state") String state);

    @Insert("""
            INSERT INTO runtime.gate_check(tenant_id,run_id,iteration_no,plan_version,check_key,verifier_version,verdict,evidence_ref)
            SELECT #{tenant},#{run},#{iteration},#{plan},#{key},gate_policy_version,#{verdict},CAST(#{evidence} AS text)
            FROM runtime.run WHERE tenant_id=#{tenant} AND id=#{run}
            """)
    void insertGate(GateRow row);

    @Insert("""
            WITH metadata AS (
                INSERT INTO runtime.context_artifact(tenant_id,run_id,id,step_id,artifact_key,version,kind,content_ref,
                  content_digest,media_type,format_version,size_bytes)
                VALUES(#{tenant},#{run},#{id},#{step},#{key},#{version},#{kind},concat('pg:',CAST(#{id} AS uuid)),
                    #{digest},'application/json',1,#{size}) RETURNING tenant_id,run_id,id
            )
            INSERT INTO runtime.artifact_body(tenant_id,run_id,artifact_id,body)
            SELECT tenant_id,run_id,id,CAST(#{body} AS jsonb) FROM metadata
            """)
    void insertArtifact(ArtifactRow row);

    @Select("""
            SELECT a.id,b.body::text,a.content_ref,a.content_digest,a.size_bytes
            FROM runtime.context_artifact a LEFT JOIN runtime.artifact_body b
              ON b.tenant_id=a.tenant_id AND b.run_id=a.run_id AND b.artifact_id=a.id
            WHERE a.tenant_id=#{tenant} AND a.run_id=#{run} AND a.id=#{id}
            """)
    Artifact artifact(@Param("tenant") String tenant, @Param("run") UUID run, @Param("id") UUID id);

    @Select("""
            SELECT DISTINCT ON(a.artifact_key) a.id,a.artifact_key FROM runtime.context_artifact a
            WHERE a.tenant_id=#{tenant} AND a.run_id=#{run} AND a.artifact_key IN ('source','region','product','customer','report','gate')
            ORDER BY a.artifact_key,a.version DESC
            """)
    List<ArtifactReference> latestObservations(@Param("tenant") String tenant, @Param("run") UUID run);

    @Update("""
            UPDATE runtime.run SET cancel_requested_at=coalesce(cancel_requested_at,now()),status='CANCEL_REQUESTED',
              terminal_reason=coalesce(terminal_reason,#{reason}),version=version+1
            WHERE tenant_id=#{tenant} AND id=#{run} AND finished_at IS NULL
            """)
    void markCancellation(@Param("tenant") String tenant, @Param("run") UUID run, @Param("reason") String reason);

    @Update("""
            UPDATE runtime.run SET status=#{status},terminal_reason=#{reason},result_ref=CAST(#{result} AS text),
              gate_pass_iteration=#{passedIteration},version=version+1,
              finished_at=CASE WHEN #{status} IN ('SUCCEEDED','FAILED','CANCELLED') THEN now() ELSE NULL END
            WHERE tenant_id=#{tenant} AND id=#{id}
            """)
    void updateRun(StateUpdate change);

    @Update("""
            UPDATE intent.task SET status=#{state},revision=revision+1,updated_at=now()
            WHERE tenant_id=#{tenant} AND id=#{task}
            """)
    void finishTask(@Param("tenant") String tenant, @Param("task") UUID task, @Param("state") String state);

    @Select(value = """
            UPDATE runtime.run SET last_event_seq=last_event_seq+1 WHERE tenant_id=#{tenant} AND id=#{run}
            RETURNING last_event_seq
            """, affectData = true)
    long nextEvent(@Param("tenant") String tenant, @Param("run") UUID run);

    @Insert("""
            WITH logged AS (
                INSERT INTO runtime.run_event(tenant_id,run_id,seq,event_id,event_type,payload)
                VALUES(#{tenant},#{run},#{seq},#{id},#{type},CAST(#{payload} AS jsonb)) RETURNING *
            )
            INSERT INTO eventing.outbox(tenant_id,event_id,event_type,aggregate_type,aggregate_id,aggregate_version,
                stream,trace_id,correlation_id,payload)
            SELECT tenant_id,event_id,event_type,'RUN',run_id,seq,'agent:events:run',#{trace},#{trace},payload FROM logged
            """)
    void appendEvent(EventRow row);

    @Select("""
            SELECT seq,event_type,payload::text FROM runtime.run_event
            WHERE tenant_id=#{tenant} AND run_id=#{run} AND seq>#{after} ORDER BY seq LIMIT #{limit}
            """)
    List<Event> events(@Param("tenant") String tenant, @Param("run") UUID run, @Param("after") long after, @Param("limit") int limit);

    @Select("""
            SELECT command_type FROM runtime.run_command
            WHERE tenant_id=#{tenant} AND run_id=#{run} AND actor_user_id=#{user} AND command_key=#{key}
            """)
    String command(@Param("tenant") String tenant, @Param("run") UUID run, @Param("user") String user, @Param("key") String key);

    @Insert("""
            INSERT INTO runtime.run_command(tenant_id,run_id,actor_user_id,command_key,command_type,request_hash,receipt)
            VALUES(#{tenant},#{run},#{user},#{key},'CANCEL','cancel-v1','{"accepted":true}')
            """)
    void insertCommand(@Param("tenant") String tenant, @Param("run") UUID run, @Param("user") String user, @Param("key") String key);
}
