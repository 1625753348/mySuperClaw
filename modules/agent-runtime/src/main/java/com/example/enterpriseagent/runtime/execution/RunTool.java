package com.example.enterpriseagent.runtime.execution;

import com.example.enterpriseagent.runtime.RunModel.Actor;
import com.example.enterpriseagent.runtime.RunModel.Source;
import com.example.enterpriseagent.runtime.RunModel.ToolStatus;

import java.util.UUID;

/** 网关仍拥有审批、外部任务与幂等调用，运行时通过相关 ID 回查。 */
public interface RunTool {
    void validate(Actor actor, Source source);
    ToolStatus submit(Actor actor, UUID runId, UUID stepId, Source source, String key);
    ToolStatus submitPinned(Actor actor, UUID runId, UUID stepId, Source source, String key, UUID versionId);
    ToolStatus read(Actor actor, UUID runId, UUID stepId, UUID executionId, boolean cancel);
}
