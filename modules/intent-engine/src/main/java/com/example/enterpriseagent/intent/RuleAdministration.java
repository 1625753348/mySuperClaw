package com.example.enterpriseagent.intent;

import java.util.List;
import java.util.Map;

public interface RuleAdministration {
    List<Version> versions(IntentModel.Actor actor, String intent, int definitionVersion);
    void save(IntentModel.Actor actor, String intent, int definitionVersion, int version, Spec spec);
    void publish(IntentModel.Actor actor, String intent, int definitionVersion, int version);

    record Spec(Map<String, Object> parameters, String clarification, Boundary safetyBoundary) {}
    record Boundary(List<String> allowedRoles, String dataScope, String approval, String effect, String constraints) {}
    record Version(int version, String status, Spec spec) {}
}
