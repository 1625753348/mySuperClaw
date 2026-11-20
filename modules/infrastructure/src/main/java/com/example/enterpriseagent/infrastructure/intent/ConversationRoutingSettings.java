package com.example.enterpriseagent.infrastructure.intent;

import com.example.enterpriseagent.decision.DecisionService.Kind;
import com.example.enterpriseagent.runtime.planner.GeneralPlanner;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

@ConfigurationProperties("enterprise.intent.routing")
public record ConversationRoutingSettings(List<Route> bindings, List<Knowledge> knowledge, List<PlannerRoute> planners) {
    public ConversationRoutingSettings {
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
        knowledge = knowledge == null ? List.of() : List.copyOf(knowledge);
        planners = planners == null ? List.of() : List.copyOf(planners);
        if (bindings.stream().map(route -> List.of(route.tenant(), route.intent())).distinct().count() != bindings.size()
                || knowledge.stream().map(Knowledge::tenant).distinct().count() != knowledge.size()
                || planners.stream().map(route -> List.of(route.tenant(), route.intent())).distinct().count() != planners.size()
                || conflictingBindings(planners, bindings)) {
            throw new IllegalArgumentException("同一租户的会话绑定或知识源重复");
        }
    }

    private static boolean conflictingBindings(List<PlannerRoute> plans, List<Route> routes) {
        return plans.stream().anyMatch(plan -> routes.stream().anyMatch(route -> route.tenant().equals(plan.tenant())
                && route.intent().equals(plan.intent()) && route.mode() != Kind.WORKFLOW));
    }

    public record PlannerRoute(String tenant, String intent, int definitionVersion, GeneralPlanner.Policy policy) {
        public PlannerRoute {
            if (tenant == null || tenant.isBlank() || tenant.contains(":") || intent == null
                    || !intent.matches("[A-Z][A-Z0-9_]*\\.[A-Z][A-Z0-9_]*") || definitionVersion < 1 || policy == null) {
                throw new IllegalArgumentException("通用规划路由无效");
            }
        }
    }

    public record Route(String tenant, String intent, Kind mode, int definitionVersion, String toolName, String toolVersion,
                        String skillKey, Map<String, String> parameters, String predecessor) {
        public Route {
            if (tenant == null || tenant.isBlank() || tenant.contains(":") || intent == null
                    || !intent.matches("[A-Z][A-Z0-9_]*\\.[A-Z][A-Z0-9_]*") || mode == null || definitionVersion < 1
                    || mode == Kind.PLAN_AND_EXECUTE && !"SALES.ANALYZE".equals(intent)
                    || mode == Kind.WORKFLOW && (skillKey == null || !skillKey.matches("[a-z][a-z0-9.-]{2,79}")
                    || predecessor != null || parameters != null && !parameters.isEmpty())) {
                throw new IllegalArgumentException("会话路由配置无效");
            }
        }
    }

    public record Knowledge(String tenant, String toolName, String toolVersion) {
        public Knowledge {
            if (tenant == null || tenant.isBlank() || toolName == null || !toolName.matches("[A-Za-z0-9_-]{1,64}")
                    || toolVersion == null || !toolVersion.matches("[A-Za-z0-9._-]{1,48}")) {
                throw new IllegalArgumentException("知识检索工具必须指定租户和固定版本");
            }
        }
    }
}
