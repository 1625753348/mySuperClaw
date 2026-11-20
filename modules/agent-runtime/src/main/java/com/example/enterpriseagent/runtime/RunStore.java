package com.example.enterpriseagent.runtime;

import java.util.List;
import java.util.UUID;

import static com.example.enterpriseagent.runtime.RunModel.*;

/** Run 锁保护计划、节点结算及事件序号，领取后的计算与网络请求在事务外执行。 */
public interface RunStore {
    int reserveSubmission(Admission data);
    Receipt receipt(String tenant, String user, String agent, String key);
    void insertAdmission(Admission data);
    int insertFixedAdmission(FixedAdmission data);
    Run owned(String tenant, String user, String agent, UUID id);
    Run lock(String tenant, UUID id);
    Receipt submission(String tenant, UUID id);
    String input(String tenant, UUID taskId);
    String fixedInput(String tenant, UUID run);
    CandidateRun lockDueRun();
    void insertPlan(PlanVersion row);
    void insertStep(StepRow row);
    void insertDependency(Dependency row);
    List<Step> steps(String tenant, UUID run, int plan);
    Step dueStep(String tenant, UUID run, int plan, boolean cancelled);
    int claimStep(String tenant, UUID id, String worker, int leaseSeconds);
    void abandonAttempt(String tenant, UUID id, int attempt);
    void startAttempt(String tenant, UUID id, String worker);
    int closeStep(StepChange change);
    void finishAttempt(String tenant, UUID id, int attempt, String state, String reason);
    void readyDependents(String tenant, UUID run, int plan);
    void cancelUnstarted(String tenant, UUID run, int plan);
    void insertProposal(ProposalRow row);
    PlanProposal dueProposal(String tenant, UUID run);
    int claimProposal(String tenant, UUID run, UUID proposal, String worker, int leaseSeconds, String plannerVersion);
    void countPlannerCall(String tenant, UUID run);
    int finishProposal(ProposalChange change);
    void cancelProposals(String tenant, UUID run);
    void activatePlan(String tenant, UUID run, int plan);
    void insertIteration(IterationRow row);
    void finishIteration(String tenant, UUID run, int iteration, String state);
    void insertGate(GateRow row);
    void insertArtifact(ArtifactRow row);
    Artifact artifact(String tenant, UUID run, UUID id);
    List<Artifact> latestObservations(String tenant, UUID run);
    void markCancellation(String tenant, UUID run, String reason);
    void updateRun(StateUpdate change);
    void finishTask(String tenant, UUID task, String state);
    long nextEvent(String tenant, UUID run);
    void appendEvent(EventRow row);
    List<Event> events(String tenant, UUID run, long after, int limit);
    String command(String tenant, UUID run, String user, String key);
    void insertCommand(String tenant, UUID run, String user, String key);

    record Admission(String tenant, String user, String agent, String key, String hash, String roles,
                     String request, UUID run, UUID task, UUID conversation, UUID turn, UUID definition,
                     UUID intent, UUID candidate, UUID decision, String route, String trace, String gate,
                     int iterations, int deadlineSeconds) {}
    record FixedAdmission(String tenant, UUID run, UUID task, int inputVersion, UUID skillVersion,
                          String user, String agent, String trace) {}
    record PlanVersion(String tenant, UUID run, int version, String origin, UUID proposal, String hash, UUID skillVersion) {
        public PlanVersion(String tenant, UUID run, int version, String origin, UUID proposal, String hash) {
            this(tenant, run, version, origin, proposal, hash, null);
        }
    }
    record StepRow(String tenant, UUID id, UUID run, int plan, String key, String kind, String spec,
                   String status, String operationKey) {}
    record Dependency(String tenant, UUID run, int plan, UUID step, UUID parent) {}
    record StepChange(String tenant, UUID step, String worker, long epoch, String state, UUID tool, UUID artifact,
                      int delayMs) {}
    record ProposalRow(String tenant, UUID run, UUID id, int base, String kind, String reason, String plannerVersion) {}
    record ProposalChange(String tenant, UUID run, UUID id, String worker, long epoch, String status,
                          String contextHash, UUID contextArtifact, UUID draftArtifact, String validation) {}
    record IterationRow(String tenant, UUID run, int iteration, int plan, String hash, String manifest) {}
    record GateRow(String tenant, UUID run, int iteration, int plan, String key, String verdict, UUID evidence) {}
    record ArtifactRow(String tenant, UUID run, UUID id, UUID step, String key, int version, String kind,
                       String digest, int size, String body) {}
    record StateUpdate(String tenant, UUID id, String status, String reason, UUID result, Integer passedIteration) {}
    record EventRow(String tenant, UUID run, long seq, UUID id, String type, String trace, String payload) {}
}
