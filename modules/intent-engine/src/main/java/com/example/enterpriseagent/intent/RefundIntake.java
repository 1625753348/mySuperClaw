package com.example.enterpriseagent.intent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.example.enterpriseagent.intent.IntentModel.*;

/** 退款 Skill 的自然语言、L0 命令和同任务补参。写入与审批交给网关。 */
final class RefundIntake {
    private static final String INTENT = "ORDER.REFUND";
    private static final String KEY = "order.refund";
    private static final String COMMAND = "/refund";
    private static final Pattern ORDER = Pattern.compile("(?i)(?<![a-z0-9])(?:SO-?[a-z0-9]{2,}|[0-9]{6,20})(?![a-z0-9])");
    private static final Pattern REASON = Pattern.compile("(?:退款原因|原因|理由|因为)[：:\\s]*(.+)");

    static Optional<WorkflowIntake.Result> resolve(WorkflowIntake.Catalog catalog, Actor actor, Request request,
            String text, Task anchored, Task focus, long now, long revision) {
        boolean command = text.equals(COMMAND) || text.startsWith(COMMAND + " ");
        boolean explicit = command || KEY.equals(request.skillKey());
        if (request.skillKey() != null && !KEY.equals(request.skillKey())) return Optional.empty();
        boolean natural = text.matches(".*(?:退款|退订单|退单).*") && !text.matches(".*(?:政策|规则|怎么退款|退款进度|退款状态).*");
        Task target = WorkflowIntake.matches(anchored, KEY) ? anchored : null;
        if (anchored != null && target == null) return Optional.empty();
        if (target == null && !explicit && !natural && WorkflowIntake.matches(focus, KEY)
                && "NEEDS_CLARIFICATION".equals(focus.status()) && (REASON.matcher(text).find() || ORDER.matcher(text).matches())) {
            target = focus;
        }
        if (!explicit && !natural && target == null) return Optional.empty();
        var selected = catalog.find(actor, KEY, target == null ? null : target.workflowSkill(), command ? COMMAND : null);
        if (selected.isEmpty() && !explicit && target == null) return Optional.empty();
        var assessment = WorkflowIntake.assessment(explicit);
        if (selected.isEmpty() || !actor.scopes().contains("refunds.submit")) {
            boolean unavailable = selected.isEmpty();
            var outcome = new Outcome(INTENT, "NEW_TASK", null, unavailable ? "UNSUPPORTED" : "DENIED", "NONE",
                    KEY, Map.of(), List.of(), List.of(), unavailable ? "当前没有可用的退款 Skill。" : "当前没有发起退款的权限。",
                    unavailable ? "SKILL_VERSION_UNAVAILABLE" : "POLICY_DENIED", List.of(), assessment, null);
            return Optional.of(new WorkflowIntake.Result(null, outcome));
        }
        var fields = new LinkedHashMap<String, Object>();
        var sources = new LinkedHashMap<String, String>();
        var invalid = new java.util.ArrayList<String>();
        var supplied = request.arguments() == null ? Map.<String, Object>of() : request.arguments();
        var order = ORDER.matcher(text);
        String idText = order.find() ? order.group() : null;
        var reason = REASON.matcher(request.text() == null ? "" : request.text());
        String reasonText = reason.find() ? reason.group(1).strip() : null;
        if (reasonText == null && target != null && idText == null && !explicit && !natural
                && request.targetTaskId() != null && request.text() != null) reasonText = request.text().strip();
        bind(target, supplied, "orderId", idText, fields, sources, invalid);
        bind(target, supplied, "reason", reasonText, fields, sources, invalid);
        var missing = List.of("orderId", "reason").stream().filter(field -> !fields.containsKey(field)).toList();
        if (missing.isEmpty() && invalid.isEmpty() && !selected.get().valid().test(fields)) invalid.add("reason");
        var skill = selected.get().skill();
        UUID id = target == null ? UUID.randomUUID() : target.id();
        boolean ready = invalid.isEmpty() && missing.isEmpty();
        String status = ready ? "READY" : "NEEDS_CLARIFICATION";
        var task = new Task(id, INTENT, Map.copyOf(fields), status, revision, now, assessment, null, skill.definitionVersion(), skill);
        var ir = ready ? new ValidatedIntentIR("ORDER", "REFUND", skill.definitionVersion(), id, fields, sources,
                null, "HIGH", ValidatedIntentIR.ResponseMode.PROGRESS_AND_RESULT, skill, null) : null;
        String question = ready ? null : !fields.containsKey("orderId") ? "请提供有效的订单号。" : "请提供退款原因。";
        var outcome = new Outcome(INTENT, target == null ? "NEW_TASK" : "CONTINUATION", id, status, "WORKFLOW",
                KEY, task.slots(), missing, invalid, question, ready ? "WORKFLOW_INPUT_VALIDATED" : "WORKFLOW_INPUT_REQUIRED",
                List.of(), assessment, ir, skill, sources);
        return Optional.of(new WorkflowIntake.Result(task, outcome));
    }

    private static void bind(Task target, Map<String, Object> supplied, String name, String text,
                             Map<String, Object> fields, Map<String, String> sources, List<String> invalid) {
        if (!supplied.containsKey(name) && text == null) {
            if (target != null && target.slots().containsKey(name)) {
                fields.put(name, target.slots().get(name));
                sources.put(name, "PRIOR_TASK");
            }
            return;
        }
        Object value = supplied.containsKey(name) ? supplied.get(name) : text;
        if (!(value instanceof String str) || str.isBlank() || str.length() > 300
                || "orderId".equals(name) && !ORDER.matcher(str.strip()).matches()) {
            invalid.add(name);
            return;
        }
        fields.put(name, "orderId".equals(name) ? str.strip().toUpperCase(Locale.ROOT) : str.strip());
        sources.put(name, "CURRENT_TURN");
    }
}
