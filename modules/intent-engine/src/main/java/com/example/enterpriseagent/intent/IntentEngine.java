package com.example.enterpriseagent.intent;

import com.example.enterpriseagent.intent.context.ContextAssembler;
import com.example.enterpriseagent.intent.context.ContextResolver;
import com.example.enterpriseagent.intent.definition.IntentCatalog;
import com.example.enterpriseagent.intent.definition.SlotExtractor;
import com.example.enterpriseagent.intent.routing.RecognitionModel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.example.enterpriseagent.intent.IntentModel.*;
import static com.example.enterpriseagent.intent.IntentArtifacts.*;
import static com.example.enterpriseagent.intent.ValidatedIntentIR.ResponseMode.*;

/** 归一化、候选识别、关系解析及槽位合并。输出可独立验收的结构化回执。 */
public final class IntentEngine {
    private static final Pattern SECRET_REQUEST = Pattern.compile(
            "系统管理员密码|system\\s*prompt|系统提示词|内部密钥|管理员凭证|无限制.{0,12}(?:伪造|造假)|伪造.{0,20}(?:审批|审批通过)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern QUERY_SYNTAX = Pattern.compile(
            "(?i)(?:['\"]\\s*;\\s*(?:drop|alter|delete|update|truncate|insert)\\s+|['\"]\\s+or\\s+\\d+\\s*=)");
    private static final Pattern SPLIT = Pattern.compile(
            "[，,。；;]?\\s*(?:顺便|另外|然后|接着|并且|并)(?=\\s*(?:查|提醒|催|发起|提交|生成|导出|发|帮|把|筛|挑))|[，,；;]\\s*(?=挑出|筛出|给.{1,10}发)");
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("date", "日期"), Map.entry("time", "时间"), Map.entry("attendees", "人数"),
            Map.entry("amount", "金额"), Map.entry("reason", "事由"), Map.entry("attachment", "附件引用"),
            Map.entry("contractId", "合同编号"), Map.entry("documentId", "单据编号"),
            Map.entry("orderId", "订单号"), Map.entry("leaveType", "请假类型"), Map.entry("duration", "时长"),
            Map.entry("city", "城市"), Map.entry("person", "人员"), Map.entry("period", "查询期间"));

    private final IntentCatalog catalog = new IntentCatalog();
    private final InputNormalizer normalizer = new InputNormalizer();
    private final ContextAssembler assembler = new ContextAssembler();
    private final ContextResolver context = new ContextResolver();
    private final ResponseComposer responses = new ResponseComposer();
    private final SlotExtractor slots;
    private final RecognitionModel model;
    private final Clock clock;
    private final WorkflowIntake workflows;

    public IntentEngine(Clock clock, RecognitionModel model) {
        this(clock, model, null);
    }

    public IntentEngine(Clock clock, RecognitionModel model, WorkflowIntake workflows) {
        this.clock = clock;
        this.model = model;
        this.slots = new SlotExtractor(clock);
        this.workflows = workflows;
    }

    public Definition definition(String id, int version) { return catalog.get(id, version); }

    public Analysis analyze(RawRequest raw, State authorized) {
        NormalizedInput input = normalizer.normalize(raw);
        Request request = raw.request();
        Actor actor = raw.actor();
        String normalized = input.text();
        String text = documentCommand(normalized);
        ContextSnapshot snapshot = assembler.assemble(input, authorized, clock.millis());
        State before = snapshot.state();
        long now = snapshot.capturedAt();
        var observation = new InputObservation(normalized, before.revision(), now, before.tasks().size(), before.recent().size());
        Session session = new Session(before, now, observation);
        if (request.targetTaskId() != null && context.find(before, request.targetTaskId()) == null) {
            throw new Failure(404, "当前会话中没有该任务");
        }
        if (normalized.length() > 4096 || has(text, "1万字|一万字|10000字")
                || SECRET_REQUEST.matcher(text).find()
                || !has(text, "^(?:解析|提取|解读)") && QUERY_SYNTAX.matcher(text).find()) {
            return session.finish(List.of(blocked(normalized.length() > 4096 ? normalized : text)), "");
        }
        if (has(text, "^(?:清空上下文|重置对话|/reset)[。!！ ]*$")) {
            session.clear();
            return session.finish(List.of(meta("SYSTEM.CLEAR", "NEW_TASK", null, "COMPLETED", "CONTEXT_CLEARED", "已重置会话上下文。")), "");
        }
        var dangerous = catalog.matches(text).stream().filter(m -> "CRITICAL".equals(m.definition().risk())).findFirst();
        boolean control = has(text, "^(?:我删错了[，, ]*)?(?:撤回|撤销|取消|不用了|算了|停掉|停止|确认|重新|再执行|执行一下刚才)")
                || has(text, "^刚才.{1,32}(?:失败|没成功).{0,5}重新发起");
        if (!control && dangerous.isPresent() && request.skillKey() == null) {
            return session.finish(List.of(apply(actor, request, text, dangerous.get().definition(), .98,
                    session, before, null, List.of(), null, false)), text);
        }
        String commandText = text.replaceFirst("^(?:算了|不用了)[，, ]*(?=(?:先)?(?:查|看|帮我查|导出))", "");
        if (!commandText.equals(text)) {
            Task target = context.find(before, before.focus());
            if (target != null && "NEEDS_CLARIFICATION".equals(target.status())) session.cancel(target);
            text = commandText;
            control = false;
        }
        if (request.skillKey() == null && control) {
            var action = control(actor, request, text, before, session);
            if (action != null) return session.finish(List.of(action), text);
        }
        if (workflows != null) {
            Task anchored = context.find(before, request.targetTaskId());
            Task focus = context.find(before, before.focus());
            if (WorkflowIntake.isWorkflow(anchored) && (!context.usable(before, anchored, now) || "CANCELLED".equals(anchored.status()))) {
                throw new Failure(409, "复盘任务已结束或过期，请重新发起");
            }
            var workflow = workflows.resolve(actor, request, text, anchored, context.usable(before, focus, now) ? focus : null,
                    now, session.revision);
            if (workflow.isPresent()) {
                var result = workflow.get();
                if (result.task() != null) session.put(result.task());
                session.classifiedIntents.put(0, result.outcome().intent());
                return session.finish(List.of(result.outcome()), text);
            }
        }
        String[] pieces = request.skillKey() == null ? SPLIT.split(text, 9) : new String[]{text};
        if (pieces.length > 8) throw new Failure(400, "同一轮最多处理八个候选");
        var results = new ArrayList<Outcome>();
        for (String piece : pieces) {
            String segment = piece.strip();
            if (segment.isEmpty() && request.skillKey() == null) continue;
            var matches = catalog.matches(segment);
            if (request.skillKey() == null && has(segment, "合同") && has(segment, "发出去|上传")
                    && has(segment, "走流程|送审")) {
                results.add(apply(actor, request, segment, catalog.get("CONTRACT.UPLOAD"), .91, session, before,
                        null, List.of(), null, false));
                results.add(apply(actor, request, segment, catalog.get("CONTRACT.APPROVAL"), .91, session, before,
                        null, List.of(results.size() - 1), null, false));
                continue;
            }
            if (request.skillKey() == null && has(segment, "报销") && has(segment, "处理一下")
                    && !has(segment, "通过|批准|查询")) {
                results.add(meta("UNKNOWN", "AMBIGUOUS", null, "NEEDS_CLARIFICATION", "ACTION_AMBIGUOUS", "是查询报销单，还是提交审批？"));
                continue;
            }
            Task explicit = context.find(session.snapshot(), request.targetTaskId());
            var recognized = recognize(segment, matches, request.skillKey(), pieces.length > 1, before);
            Definition definition = recognized.definition();
            session.classifiedIntents.put(results.size(), definition == null ? "UNKNOWN" : definition.id());
            Task target = target(session.snapshot(), explicit, definition, segment, now);
            if (target == null && definition == null && session.tasks.stream().filter(t -> "NEEDS_CLARIFICATION".equals(t.status()))
                    .filter(t -> context.usable(before, t, now)).filter(t -> hasSlots(t, segment)).count() > 1) {
                results.add(meta("UNKNOWN", "AMBIGUOUS", null, "NEEDS_CLARIFICATION", "TARGET_AMBIGUOUS", "有多个任务可接收这些参数。请指定要继续的任务。"));
                continue;
            }
            if (target != null) definition = definition(target.intent(), target.definitionVersion());
            if (definition == null) {
                boolean vague = has(segment, "那个|那件|弄一下|处理一下|那个东西");
                results.add(meta("UNKNOWN", vague ? "AMBIGUOUS" : "NEW_TASK", null,
                        vague ? "NEEDS_CLARIFICATION" : "UNSUPPORTED", vague ? "INTENT_AMBIGUOUS" : "NO_MATCH",
                        vague ? "请说明要处理的对象和动作。" : "暂未找到对应的业务能力。"));
                continue;
            }
            List<Integer> dependencies = dependencies(definition.id(), results);
            results.add(apply(actor, request, segment, definition, recognized.confidence(), session, before,
                    target, dependencies, recognized.strategy(), pieces.length > 1));
        }
        if (results.isEmpty()) results.add(meta("UNKNOWN", "AMBIGUOUS", null, "NEEDS_CLARIFICATION", "EMPTY_INPUT", "请说明你想完成的任务。"));
        return session.finish(results, text);
    }

    private IntentCandidate recognize(String text, List<IntentCatalog.Match> matches, String key, boolean multi, State state) {
        if (key != null) {
            Definition definition = catalog.skill(key);
            if (definition == null) throw new Failure(400, "该 Skill Key 尚无意图定义");
            return new IntentCandidate(definition, 1, "RULE");
        }
        if (has(text, "什么模型|你是哪个模型") && !SECRET_REQUEST.matcher(text).find()) {
            return new IntentCandidate(catalog.get("KNOWLEDGE.QUESTION"), .82, null);
        }
        if (has(text, "^(?:报销|提交报销(?:单)?[a-z0-9_-]*|发起报销|申请报销)$")) {
            return new IntentCandidate(catalog.get("EXPENSE.CREATE"), .93, null);
        }
        if (has(text, "(?:交|提交)周报") && has(text, "提醒|催")) {
            return new IntentCandidate(catalog.get("NOTIFICATION.REMIND"), .92, null);
        }
        if (has(text, "续签|续约") && has(text, "审批|发起")) {
            return new IntentCandidate(catalog.get("CONTRACT.RENEW"), .93, null);
        }
        if (has(text, "审批|审核") && has(text, "把这笔|直接批|批掉|同时点通过")) {
            return new IntentCandidate(catalog.get("APPROVAL.APPROVE"), .93, null);
        }
        if (has(text, "有哪几种|是多少|是什么|上限是多少|什么规定|上限|怎么理解") && !has(text, "先帮我|顺便")) {
            return new IntentCandidate(catalog.get("KNOWLEDGE.QUESTION"), .92, null);
        }
        if (matches.isEmpty() && text.length() > 12 && has(text, "业务|订单|出差|请假|查|合同|审批|报销")) {
            return choose(text, catalog.capabilities(), .65);
        }
        if (matches.isEmpty()) return new IntentCandidate(null, .78, null);
        var top = matches.getFirst();
        if (matches.size() > 1 && (multi || text.length() >= 27) && "NORMAL".equals(top.definition().risk())) {
            var help = choose(text, matches.stream().limit(5).map(m -> m.definition().id()).toList(), .84);
            if (help.definition() != null) return help;
        }
        return new IntentCandidate(top.definition(), top.score(), null);
    }

    private IntentCandidate choose(String text, List<String> allowed, double confidence) {
        try {
            // 任务中仅使用当前片段，模型请求不附带会话正文、用户身份或工具凭证。
            Definition selected = model.choose(text, allowed).filter(allowed::contains).map(catalog::get)
                    .filter(d -> "NORMAL".equals(d.risk())).orElse(null);
            return new IntentCandidate(selected, confidence, selected == null ? null : "LARGE_MODEL");
        } catch (RuntimeException ex) {
            return new IntentCandidate(null, confidence, null);
        }
    }

    private Task target(State state, Task explicit, Definition definition, String text, long now) {
        if (explicit != null && !"CANCELLED".equals(explicit.status()) && context.usable(state, explicit, now)
                && (definition == null || definition.id().equals(explicit.intent()))) return explicit;
        Task focus = context.find(state, state.focus());
        if (context.usable(state, focus, now) && hasSlots(focus, text)
                && (definition == null || definition.id().equals(focus.intent()))
                && (has(text, "改成|改为|换成|换个|呢[？?。]?$") || "NEEDS_CLARIFICATION".equals(focus.status()))) {
            return focus;
        }
        if (definition == null) {
            List<Task> pending = context.pending(state, null, now).stream().filter(task -> hasSlots(task, text)).toList();
            return pending.size() == 1 ? pending.getFirst() : null;
        }
        List<Task> pending = context.pending(state, definition.id(), now);
        if (pending.size() == 1 && hasSlots(pending.getFirst(), text)
                && !has(text, "再发起|重新|另一个|新建")) return pending.getFirst();
        return null;
    }

    private boolean hasSlots(Task task, String text) {
        if (WorkflowIntake.isWorkflow(task)) return false;
        var extracted = slots.extract(definition(task.intent(), task.definitionVersion()), text, Map.of());
        return !extracted.values().isEmpty() || !extracted.invalid().isEmpty();
    }

    private Outcome apply(Actor actor, Request request, String text, Definition def, double confidence,
                          Session session, State before, Task target, List<Integer> dependencies, String strategy, boolean multi) {
        if ("SYSTEM.HELP".equals(def.id())) {
            return meta(def.id(), "NEW_TASK", null, "COMPLETED", "CAPABILITY_LIST",
                    "当前可识别的业务请求包括 " + String.join("、", catalog.capabilities()) + "。");
        }
        var detected = slots.extract(def, text, request.arguments() == null ? Map.of() : request.arguments());
        var values = new LinkedHashMap<String, Object>();
        var provenance = new LinkedHashMap<String, String>();
        if (target != null) target.slots().forEach((k, v) -> { values.put(k, v); provenance.put(k, "PRIOR_TASK"); });
        if (has(text, "它|他|她|这份|这笔|这个|刚才|那份|那张")) {
            for (String field : List.of("person", "contractId", "documentId")) {
                if ((def.required().contains(field) || def.optional().contains(field)) && !values.containsKey(field)
                        && session.entities.containsKey(field) && session.tasks.stream().anyMatch(t ->
                        context.usable(before, t, session.now) && java.util.Objects.equals(t.slots().get(field), session.entities.get(field)))) {
                    values.put(field, session.entities.get(field));
                    provenance.put(field, "SESSION_ENTITY");
                }
            }
        }
        detected.invalid().forEach(field -> { values.remove(field); provenance.remove(field); });
        detected.values().forEach((field, value) -> { values.put(field, value); provenance.put(field, "CURRENT_TURN"); });
        if (("CONTRACT.APPROVAL".equals(def.id()) || "PURCHASE.CREATE".equals(def.id()))
                && values.get("amount") instanceof Number amount) {
            if (amount.doubleValue() >= 100_000) values.put("approvalChain", List.of("部门主管", "财务总监"));
            else if (amount.doubleValue() >= 50_000) values.put("approvalChain", List.of("部门主管", "财务"));
        }
        ParameterSchema schema = def.parameterSchema();
        var slotState = new SlotState(schema.required().stream().filter(field -> !values.containsKey(field)).toList(),
                detected.invalid());
        var missing = slotState.missing();
        boolean critical = "CRITICAL".equals(def.risk());
        String relation = target == null ? "NEW_TASK" : has(text, "改成|改为|换成|呢[？?。]?$") ? "MODIFICATION" : "CONTINUATION";
        UUID id = target == null ? UUID.randomUUID() : target.id();
        var assessment = assess(actor, def, id, target, text, confidence, strategy, critical, multi);
        if (def.requiredScope() != null && !actor.scopes().contains(def.requiredScope()) || critical && def.skill() == null
                || "ACCOUNT.PASSWORD_CHANGE".equals(def.id()) || has(text, "替.{1,12}批掉|冒充.{1,12}审批")) {
            return outcome(def, relation, null, "DENIED", Map.of(), List.of(), List.of(),
                    "当前身份或业务策略未授权该操作。", "POLICY_DENIED", List.of(), assessment, null);
        }
        if ("CHAT".equals(def.route()) || "KNOWLEDGE".equals(def.route())) {
            return outcome(def, "NEW_TASK", null, "ROUTED", Map.of(), List.of(), List.of(),
                    null, "CONVERSATION_ROUTE", List.of(), assessment, null);
        }
        Task last = context.latest(before, def.id());
        boolean repeatsDocument = request.targetTaskId() == null && request.replyToMessageId() == null
                && detected.values().containsKey("documentId") && has(text, "提交|发起|申请");
        if ((target == null || repeatsDocument) && last != null && !"CANCELLED".equals(last.status())
                && context.usable(before, last, session.now) && values.containsKey("documentId")
                && sameSlots(values, last.slots()) && isWrite(def)) {
            return outcome(def, "DUPLICATE", last.id(), "DUPLICATE", values, List.of(), List.of(),
                    "同一会话已有相同单据请求。", "DUPLICATE_TASK", List.of(), assessment, null);
        }
        String status, reason, question = null;
        ValidatedIntentIR ir = null;
        if (!dependencies.isEmpty()) {
            status = "WAITING_DEPENDENCY"; reason = "DEPENDENCY_RESULT_REQUIRED";
        } else if (!slotState.complete()) {
            status = "NEEDS_CLARIFICATION"; reason = slotState.invalid().isEmpty() ? "REQUIRED_SLOTS" : "INVALID_SLOTS";
            question = question(missing, slotState.invalid(), text);
        } else if (critical) {
            status = "WAITING_CONFIRMATION"; reason = "CONFIRMATION_REQUIRED";
            question = "请确认这项操作及其对象。";
        } else {
            status = "READY"; reason = "VALIDATED";
            ir = ir(def, id, values, provenance);
        }
        session.put(new Task(id, def.id(), values, status, session.revision, session.now, assessment,
                "WAITING_CONFIRMATION".equals(status) ? confirmation(def.id(), values) : null, def.version()));
        for (String key : List.of("person", "contractId", "documentId")) {
            if (values.containsKey(key)) session.entities.put(key, values.get(key));
        }
        return outcome(def, relation, id, status, values, missing, detected.invalid(), question,
                reason, dependencies, assessment, ir);
    }

    private Outcome control(Actor actor, Request request, String text, State before, Session session) {
        String action = has(text, "^确认") ? "CONFIRM" : has(text, "撤销|撤回|删错") ? "UNDO"
                : has(text, "重新|再执行|执行一下刚才") ? "REPLAY" : "CANCEL";
        String match = inferredTarget(text, action);
        if (request.targetTaskId() == null && match == null && !"CONFIRM".equals(action)
                && before.tasks().stream().filter(t -> t.touched() == before.revision())
                .filter(t -> context.usable(before, t, session.now)).count() > 1) {
            return meta("SYSTEM." + action, "AMBIGUOUS", null, "NEEDS_CLARIFICATION",
                    "TARGET_AMBIGUOUS", "上一轮有多个任务，请指定要操作的任务。");
        }
        Task target = request.targetTaskId() == null ? context.latest(before, match) : context.find(before, request.targetTaskId());
        if (target == null && "UNDO".equals(action) && has(text, "采购申请|请假申请|审批")
                && !has(text, "刚才|刚发起|上次")) return null;
        if (target == null || !context.usable(before, target, session.now)) {
            return meta("SYSTEM." + action, action, null, "DENIED", "TASK_UNAVAILABLE", "请在当前会话中重新指定任务。");
        }
        if (WorkflowIntake.isWorkflow(target) && !"CANCEL".equals(action)) {
            return meta("SYSTEM." + action, action, target.id(), "DENIED", "WORKFLOW_CONTROL_UNAVAILABLE",
                    "可回复补参或取消本任务。");
        }
        Definition def = definition(target.intent(), target.definitionVersion());
        if ("CONFIRM".equals(action)) {
            var confirmationSlots = slots.extract(def, text, request.arguments() == null ? Map.of() : request.arguments());
            if (!"WAITING_CONFIRMATION".equals(target.status()) || target.confirmationHash() == null
                    || session.now - target.updatedAt() > 120_000 || def.requiredScope() == null
                    || !actor.scopes().contains(def.requiredScope())
                    || request.targetTaskId() == null && !target.id().equals(before.focus())
                    || !confirmationSlots.invalid().isEmpty()
                    || confirmationSlots.values().entrySet().stream().anyMatch(it -> !java.util.Objects.equals(target.slots().get(it.getKey()), it.getValue()))
                    || !target.confirmationHash().equals(confirmation(def.id(), target.slots()))) {
                return meta("SYSTEM.CONFIRM", action, target.id(), "DENIED", "CONFIRMATION_NOT_VALID", "请重新发起确认。");
            }
            session.put(new Task(target.id(), def.id(), target.slots(), "READY", session.revision, session.now,
                    target.assessment(), null, def.version()));
            return outcome(def, "CONFIRM", target.id(), "READY", target.slots(), List.of(), List.of(), null,
                    "USER_CONFIRMED", List.of(), target.assessment(), ir(def, target.id(), target.slots(), contextProvenance(target)));
        }
        if ("CANCEL".equals(action)) {
            if ("COMPLETED".equals(target.status()) || "CANCELLED".equals(target.status())) {
                return meta("SYSTEM.CANCEL", action, target.id(), "DENIED", "TASK_NOT_CANCELLABLE", "该任务已结束。");
            }
            session.cancel(target);
            return meta("SYSTEM.CANCEL", action, target.id(), "CANCELLED", "CANCELLATION_INTENT_ACCEPTED", "已取消当前意图待办。");
        }
        if ("UNDO".equals(action)) {
            return meta("SYSTEM.UNDO", action, target.id(), "DENIED", "VERIFIED_COMPENSATION_REQUIRED", "撤回需要业务系统提供可核实的原操作。");
        }
        if ("CRITICAL".equals(def.risk()) || def.requiredScope() != null && !actor.scopes().contains(def.requiredScope())) {
            return meta("SYSTEM.REPLAY", action, target.id(), "DENIED", "REPLAY_NOT_AUTHORIZED", "请重新发起并完成授权。");
        }
        return apply(actor, request, "", def, .93, session, before, target, List.of(), "RULE", false)
                .withRelation("REPLAY");
    }

    private static String inferredTarget(String text, String action) {
        if (has(text, "提醒")) return "NOTIFICATION.REMIND";
        if (has(text, "报表|财报")) return "FINANCE.REPORT";
        if (has(text, "请假")) return "LEAVE.CREATE";
        if (has(text, "会议室")) return "MEETING.BOOK";
        if (has(text, "密码")) return "ACCOUNT.PASSWORD_CHANGE";
        if (has(text, "采购")) return "PURCHASE.CREATE";
        if (has(text, "报销") && "REPLAY".equals(action)) return "EXPENSE.QUERY";
        if (has(text, "合同") && has(text, "删除")) return "CONTRACT.DELETE";
        return null;
    }

    private static List<Integer> dependencies(String intent, List<Outcome> before) {
        if (before.isEmpty()) return List.of();
        String previous = before.getLast().intent();
        if ("CONTRACT.RENEW".equals(intent) && "CONTRACT.QUERY".equals(previous)
                || "EXPENSE.FILTER".equals(intent) && "EXPENSE.STATS".equals(previous)
                || "EMAIL.SEND".equals(intent) && "EXPENSE.FILTER".equals(previous)) return List.of(before.size() - 1);
        return List.of();
    }

    private static Map<String, String> contextProvenance(Task task) {
        var map = new LinkedHashMap<String, String>();
        task.slots().keySet().forEach(key -> map.put(key, "CONFIRMED_TASK"));
        return map;
    }

    private static ValidatedIntentIR ir(Definition definition, UUID task, Map<String, Object> values, Map<String, String> provenance) {
        String[] parts = definition.id().split("\\.", 2);
        return new ValidatedIntentIR(parts[0], parts[1], definition.parameterSchema().definitionVersion(), task,
                Map.copyOf(values), Map.copyOf(provenance), null, definition.risk(),
                "AGENT".equals(definition.route()) ? PROGRESS_AND_RESULT : RESULT);
    }

    private static boolean isWrite(Definition def) { return "WORKFLOW".equals(def.route()); }

    private static boolean sameSlots(Map<String, Object> left, Map<String, Object> right) {
        if (!left.keySet().equals(right.keySet())) return false;
        return left.entrySet().stream().allMatch(entry -> {
            Object other = right.get(entry.getKey());
            if (entry.getValue() instanceof Number number && other instanceof Number previous) {
                return new java.math.BigDecimal(number.toString()).compareTo(new java.math.BigDecimal(previous.toString())) == 0;
            }
            return java.util.Objects.equals(entry.getValue(), other);
        });
    }

    private static Assessment assess(Actor actor, Definition def, UUID taskId, Task target, String text, double confidence,
                                     String strategy, boolean critical, boolean multi) {
        double score = multi || "LARGE_MODEL".equals(strategy) ? .78
                : critical || "RULE".equals(strategy) || text.length() < 9 && target == null ? .22
                : target != null || text.length() >= 28 ? .58 : .38;
        String band = score < .30 ? "RULE" : score < .50 ? "RETRIEVAL_SMALL_MODEL" : score < .70 ? "HYSTERESIS" : "LARGE_MODEL";
        String comparison = fingerprint(def.id() + "|" + actor.agent() + "|" + actor.scopes().stream().sorted().toList()
                + "|" + actor.roles().stream().sorted().toList() + "|" + taskId);
        String applied = strategy == null ? (score < .30 ? "RULE" : score < .50 ? "RETRIEVAL_SMALL_MODEL" : "HYBRID") : strategy;
        if (critical) applied = "RULE";
        else if ("HYSTERESIS".equals(band) && target != null && target.assessment() != null
                && comparison.equals(target.assessment().comparisonKey())) applied = target.assessment().strategy();
        return new Assessment(score, confidence, band, applied, critical, comparison);
    }

    private static Outcome blocked(String text) {
        return meta("SECURITY.BLOCKED", "NEW_TASK", null, "DENIED", text.length() > 4096 || has(text, "1万字|一万字|10000字")
                ? "INPUT_LENGTH" : "UNSAFE_INPUT", "该输入未通过安全校验。");
    }

    private static Outcome meta(String id, String relation, UUID task, String status, String reason, String question) {
        return new Outcome(id, relation, task, status, "NONE", null, Map.of(), List.of(), List.of(), question, reason,
                List.of(), new Assessment(.18, .95, "RULE", "RULE", "SECURITY.BLOCKED".equals(id), null), null);
    }

    private static Outcome outcome(Definition definition, String relation, UUID id, String status, Map<String, Object> values,
                                   List<String> missing, List<String> invalid, String question, String reason,
                                   List<Integer> dependencies, Assessment assessment, ValidatedIntentIR ir) {
        return new Outcome(definition.id(), relation, id, status, definition.route(), definition.skill(), Map.copyOf(values),
                missing, invalid, question, reason, dependencies, assessment, ir);
    }

    private static String question(List<String> missing, List<String> invalid, String text) {
        if (!invalid.isEmpty()) return "请更正" + labels(invalid) + "。";
        if (missing.contains("attachment") && has(text, "附件已传|已上传")) return "请提供已上传附件的引用。";
        return "请补充" + labels(missing) + "。";
    }
    private static String labels(List<String> fields) { return String.join("、", fields.stream().map(f -> LABELS.getOrDefault(f, f)).toList()); }

    private static String documentCommand(String value) {
        if (!has(value, "^(?:请|帮我|给我|麻烦)?\\s*(?:解析|提取|解读)")) return value;
        return value.split("\\((?:内含|正文|内容|文中)|(?:正文|内容)如下[:：\\s]*", 2)[0].strip();
    }
    private static boolean has(String text, String expression) { return Pattern.compile(expression).matcher(text).find(); }
    public static String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    private static String confirmation(String intent, Map<String, Object> slots) {
        return fingerprint(intent + new java.util.TreeMap<>(slots));
    }
    private final class Session {
        private final List<Task> tasks;
        private final long revision, now;
        private final InputObservation observation;
        private final Map<Integer, String> classifiedIntents = new LinkedHashMap<>();
        private final Map<String, Object> entities;
        private final List<String> recent;
        private String summary;
        private long privacyRevision;
        private UUID focus;

        Session(State before, long now, InputObservation observation) {
            this.tasks = new ArrayList<>(before.tasks());
            this.revision = before.revision() + 1;
            this.now = now;
            this.observation = observation;
            this.entities = new LinkedHashMap<>(before.entities());
            this.recent = new ArrayList<>(before.recent());
            this.summary = before.summary();
            this.privacyRevision = before.privacyRevision();
            this.focus = before.focus();
        }

        void put(Task task) {
            tasks.removeIf(item -> item.id().equals(task.id()));
            tasks.add(task);
            if (tasks.size() > 24) tasks.removeFirst();
            focus = task.id();
        }
        void cancel(Task task) {
            put(new Task(task.id(), task.intent(), task.slots(), "CANCELLED", revision, now,
                    task.assessment(), null, task.definitionVersion(), task.workflowSkill()));
        }
        void clear() {
            tasks.clear(); entities.clear(); recent.clear(); summary = ""; focus = null; privacyRevision++;
        }
        State snapshot() { return new State(revision, List.copyOf(tasks), focus, Map.copyOf(entities), List.copyOf(recent), summary, privacyRevision); }
        Analysis finish(List<Outcome> outcomes, String safeText) {
            if (!safeText.isEmpty()) {
                recent.add(String.join(",", outcomes.stream().map(Outcome::intent).toList()));
                if (recent.size() > 8) {
                    summary = String.join(",", Arrays.stream((summary + "," + recent.removeFirst()).split(","))
                            .filter(s -> !s.isBlank()).distinct().limit(20).toList());
                }
            }
            return new Analysis(List.copyOf(outcomes), snapshot(), responses.reply(outcomes), observation,
                    Map.copyOf(classifiedIntents));
        }
    }
}
