package com.example.enterpriseagent.intent;

import com.example.enterpriseagent.intent.context.ContextAssembler;
import com.example.enterpriseagent.intent.context.ContextResolver;
import com.example.enterpriseagent.intent.context.SlotMerger;
import com.example.enterpriseagent.intent.definition.IntentCatalog;
import com.example.enterpriseagent.intent.definition.SlotExtractor;
import com.example.enterpriseagent.intent.routing.RecognitionModel;
import com.example.enterpriseagent.intent.routing.IntentRoutingPolicy;
import com.example.enterpriseagent.intent.routing.IntentRecall;

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
    private static final Pattern OPERATION_REQUEST = Pattern.compile(
            "^(?:(?:请问|请|麻烦|能不能|可以|我要|我想|帮我|替我|给我)[，,\\s]*)*"
                    + "(?:启动|打开|关闭|部署|安装|删除|取消|修改|更新|查询|核对|导出|下载|上传|提交|发起|支付|转账|发送)"
                    + "(?:[\\s，,]*.+)");
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("date", "日期"), Map.entry("time", "时间"), Map.entry("attendees", "人数"),
            Map.entry("amount", "金额"), Map.entry("reason", "事由"), Map.entry("attachment", "附件引用"),
            Map.entry("contractId", "合同编号"), Map.entry("documentId", "单据编号"),
            Map.entry("orderId", "订单号"), Map.entry("leaveType", "请假类型"), Map.entry("duration", "时长"),
            Map.entry("city", "城市"), Map.entry("person", "人员"), Map.entry("period", "查询期间"));

    private final IntentCatalog catalog = new IntentCatalog();
    private final InputNormalizer normalizer = new InputNormalizer();
    private final ContextAssembler assembler;
    private final ContextResolver context = new ContextResolver();
    private final SlotMerger merger = new SlotMerger();
    private final ResponseComposer responses = new ResponseComposer();
    private final SlotExtractor slots;
    private final RecognitionModel model;
    private final Clock clock;
    private final WorkflowIntake workflows;
    private final IntentRoutingPolicy routingPolicy;
    private final IntentRecall recall;

    public IntentEngine(Clock clock, RecognitionModel model) {
        this(clock, model, null);
    }

    public IntentEngine(Clock clock, RecognitionModel model, WorkflowIntake workflows) {
        this(clock, model, workflows, null);
    }

    public IntentEngine(Clock clock, RecognitionModel model, WorkflowIntake workflows, ContextAssembler.Reader memory) {
        this(clock, model, workflows, memory, null, null);
    }

    public IntentEngine(Clock clock, RecognitionModel model, WorkflowIntake workflows, ContextAssembler.Reader memory,
                        IntentRoutingPolicy routingPolicy, IntentRecall.Embedder embedder) {
        this.clock = clock;
        this.model = model;
        this.slots = new SlotExtractor(clock);
        this.workflows = workflows;
        this.assembler = new ContextAssembler(memory);
        this.routingPolicy = routingPolicy;
        if (routingPolicy == null) {
            recall = null;
        } else {
            var documents = new LinkedHashMap<>(catalog.routingDocuments());
            routingPolicy.examples().forEach((id, text) -> {
                if (!documents.containsKey(id) || text.length() > 1000) throw new IllegalArgumentException("意图召回示例无效：" + id);
                documents.merge(id, text, (original, example) -> original + " " + example);
            });
            recall = new IntentRecall(documents, embedder);
        }
    }

    public Definition definition(String id, int version) { return catalog.get(id, version); }

    public Analysis analyze(RawRequest raw, State authorized) {
        return analyze(raw, authorized, null);
    }

    public Analysis analyze(RawRequest raw, State authorized, ContextAssembler.Source source) {
        NormalizedInput input = normalizer.normalize(raw);
        Request request = raw.request();
        Actor actor = raw.actor();
        String normalized = input.text();
        String text = documentCommand(normalized);
        ContextSnapshot snapshot = assembler.assemble(input, authorized, clock.millis());
        State before = snapshot.state();
        long now = snapshot.capturedAt();
        Session session = new Session(actor, input, snapshot, source);
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
        var recall = request.skillKey() == null ? ConversationRecall.parse(normalized) : null;
        if (recall != null) {
            session.classifiedIntents.put(0, recall.intent());
            if (recall.limit() == 0) {
                return session.finish(List.of(meta(recall.intent(), "AMBIGUOUS", null, "NEEDS_CLARIFICATION",
                        "CONVERSATION_HISTORY_RANGE_INVALID", "请指定 1 到 " + ConversationRecall.MAX_TURNS + " 轮。")), text);
            }
            var pending = recall.document()
                    ? meta(recall.intent(), "NEW_TASK", null, "ROUTED", "CONVERSATION_DOCUMENT_RECOGNIZED",
                            "已识别到整理当前会话为 Markdown 文档的请求。")
                    : meta(recall.intent(), "NEW_TASK", null, "COMPLETED", "CONVERSATION_HISTORY_EMPTY",
                            "当前会话在指定范围内没有可回顾的消息。");
            return session.finish(List.of(new Outcome(pending.intent(), pending.relation(), null, pending.status(), pending.route(),
                    null, Map.of("offset", recall.offset(), "limit", recall.limit(), "format", recall.document() ? "MARKDOWN" : "TEXT"),
                    List.of(), List.of(), pending.question(), pending.reason(), List.of(), pending.assessment(), null)), text);
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
        String[] pieces = request.skillKey() == null ? SPLIT.split(text, 9) : new String[]{text};
        if (pieces.length > 8) throw new Failure(400, "同一轮最多处理八个候选");
        var boundaries = SPLIT.matcher(text).results().toList();
        String[] rawPieces = request.text() == null ? new String[0] : SPLIT.split(request.text(), 9);
        var results = new ArrayList<Outcome>();
        for (int index = 0; index < pieces.length; index++) {
            String segment = pieces[index].strip();
            if (segment.isEmpty() && request.skillKey() == null) continue;
            boolean independent = index > 0 && has(boundaries.get(index - 1).group(), "另外|顺便");
            Request current = pieces.length == 1 ? request : new Request(
                    rawPieces.length == pieces.length ? rawPieces[index].strip() : segment, request.skillKey(),
                    request.arguments(), independent ? null : request.targetTaskId(), independent ? null : request.replyToMessageId());
            Outcome workflow = workflow(actor, current, segment, before, session, results.size(), independent);
            if (workflow != null) {
                results.add(workflow);
                continue;
            }
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
            Definition rule = matches.isEmpty() ? null : matches.getFirst().definition();
            Task linked = routingPolicy == null ? null
                    : context.resolve(snapshot, current.targetTaskId(), rule == null ? null : rule.id(), segment,
                            independent, task -> matchingFields(task, segment)).task();
            // 单独回复编号时，业务动作沿用待补参任务，即使「报销单」也会命中查询关键词。
            boolean identifierReply = request.skillKey() == null && slots.isLabeledIdentifierReply(segment);
            var recognized = identifierReply ? new IntentCandidate(null, 1, "RULE")
                    : recognize(actor, segment, matches, request.skillKey(), pieces.length > 1, linked);
            Definition definition = recognized.definition();
            session.classifiedIntents.put(results.size(), definition == null ? "UNKNOWN" : definition.id());
            boolean conversation = definition != null && (List.of("CHAT", "KNOWLEDGE", "NONE").contains(definition.route()));
            var resolved = context.resolve(conversation ? snapshot : session.context(), current.targetTaskId(),
                    definition == null ? null : definition.id(), segment, independent, task -> matchingFields(task, segment));
            session.relations.put(results.size(), resolved.relation());
            Task target = resolved.task();
            if (resolved.relation().needsDisambiguation()) {
                results.add(meta("UNKNOWN", "AMBIGUOUS", null, "NEEDS_CLARIFICATION", "TARGET_AMBIGUOUS", "有多个任务可接收这些参数。请指定要继续的任务。"));
                continue;
            }
            if (target != null) definition = definition(target.intent(), target.definitionVersion());
            if (definition == null) {
                results.add(unresolved(segment, recognized));
                continue;
            }
            List<Integer> dependencies = dependencies(definition.id(), results);
            results.add(apply(actor, current, segment, definition, recognized.confidence(), session, before,
                    target, dependencies, recognized.strategy(), pieces.length > 1, recognized.routing(), resolved.relation().type()));
        }
        if (results.isEmpty()) results.add(meta("UNKNOWN", "AMBIGUOUS", null, "NEEDS_CLARIFICATION", "EMPTY_INPUT", "请说明你想完成的任务。"));
        return session.finish(results, text);
    }

    private Outcome workflow(Actor actor, Request request, String text, State before, Session session, int ordinal, boolean independent) {
        if (workflows == null) return null;
        Task anchored = context.find(before, request.targetTaskId());
        Task focus = independent ? null : context.find(before, before.focus());
        if (WorkflowIntake.isWorkflow(anchored)
                && (!context.usable(before, anchored, session.now) || "CANCELLED".equals(anchored.status()))) {
            throw new Failure(409, "流程任务已结束或过期，请重新发起");
        }
        if (request.skillKey() == null && anchored == null && !independent
                && before.tasks().stream().anyMatch(task -> "ORDER.REFUND".equals(task.intent()))
                && !RefundIntake.matchingFields(text).isEmpty()) {
            var selected = context.resolve(session.context(), null, null, text, false, task -> matchingFields(task, text));
            if (selected.relation().needsDisambiguation()) {
                session.relations.put(ordinal, selected.relation());
                return meta("UNKNOWN", "AMBIGUOUS", null, "NEEDS_CLARIFICATION", "TARGET_AMBIGUOUS",
                        "有多个任务可接收这些参数。请指定要继续的任务。");
            }
            focus = selected.task();
        }
        var workflow = workflows.resolve(actor, request, text, anchored,
                context.usable(before, focus, session.now) ? focus : null, session.now, session.revision);
        if (workflow.isEmpty()) return null;
        var result = workflow.get();
        if (result.task() != null) session.put(result.task());
        var outcome = result.outcome();
        session.classifiedIntents.put(ordinal, outcome.intent());
        Task prior = context.find(before, outcome.taskId());
        if (prior != null) {
            session.relations.put(ordinal, context.resolved(session.context(), prior, outcome.relation(),
                    anchored == null ? "WORKFLOW_CONTINUATION" : "EXPLICIT_TASK_REFERENCE",
                    outcome.slotProvenance().entrySet().stream().filter(slot -> "CURRENT_TURN".equals(slot.getValue()))
                            .map(Map.Entry::getKey).sorted().toList()).relation());
        }
        return outcome;
    }

    private Outcome unresolved(String text, IntentCandidate candidate) {
        Assessment assessment = routingAssessment(candidate, null);
        if (candidate.outOfScope()) {
            if (assessment == null) assessment = new Assessment(.78, 0, "LARGE_MODEL", "LARGE_MODEL",
                    false, null, null, List.of(), true);
            return meta("UNKNOWN", "NEW_TASK", null, "UNSUPPORTED", "MODEL_OUT_OF_SCOPE",
                    "我暂时找不到适合这个请求的业务能力。你可以再说说你想达到的目标。", assessment);
        }
        if (catalog.recognizesSubject(text)) {
            String question = has(text, "审批")
                    ? "你是想提交审批吗？如果是，请回复「提交审批」。如果要查询待办，请回复「查询审批待办」。"
                    : "请补充你希望执行的动作，例如查询、提交或修改。";
            return meta("UNKNOWN", "AMBIGUOUS", null, "NEEDS_CLARIFICATION", "BUSINESS_ACTION_UNCLEAR", question, assessment);
        }
        if (has(text, "那个|那件|弄一下|处理一下|那个东西")) {
            return meta("UNKNOWN", "AMBIGUOUS", null, "NEEDS_CLARIFICATION", "INTENT_AMBIGUOUS",
                    "请说明要处理的对象和动作。", assessment);
        }
        if (OPERATION_REQUEST.matcher(text).matches()) {
            return meta("UNKNOWN", "NEW_TASK", null, "UNSUPPORTED", "NO_MATCH",
                    "这个操作暂不支持。你可以告诉我想达到什么目标，我可以帮你想想其他办法。", assessment);
        }
        if (assessment == null) assessment = new Assessment(.18, 0, "RULE", "RULE", false, null);
        return outcome(catalog.get("CHAT.SMALLTALK"), "NEW_TASK", null, "ROUTED", Map.of(), List.of(), List.of(),
                "我在。你想聊些什么，或者有什么想让我帮忙的？", "CONVERSATIONAL_FALLBACK", List.of(), assessment, null);
    }

    private IntentCandidate recognize(Actor actor, String text, List<IntentCatalog.Match> matches, String key, boolean multi, Task linked) {
        if (key != null) {
            Definition definition = catalog.skill(key);
            if (definition == null) throw new Failure(400, "该 Skill Key 尚无意图定义");
            return new IntentCandidate(definition, 1, "RULE");
        }
        // 助手身份属于系统对话，精确匹配，避免拦截查询人员等业务请求。
        if (has(text, "^(?:请问[，,\\s]*)?(?:你是谁|您是谁|你是哪位|您是哪位|你叫什么(?:名字)?|你是什么助手|请?介绍(?:一下)?(?:你|您)?自己)(?:呀|啊|呢)?[\\s。！？?!]*$")) {
            return new IntentCandidate(catalog.get("SYSTEM.IDENTITY"), 1, "RULE");
        }
        var orderList = matches.stream().filter(match -> "ORDER.LIST".equals(match.definition().id())).findFirst();
        if (orderList.isPresent() && matches.stream().noneMatch(match -> !"NORMAL".equals(match.definition().risk()))) {
            return new IntentCandidate(orderList.get().definition(), .96, "RULE");
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
        // 简短的业务片段如果只有对象线索，优先澄清，避免语义召回把它猜成明确的业务动作。
        if (matches.isEmpty() && linked == null && text.length() <= 12 && catalog.recognizesSubject(text)) {
            return new IntentCandidate(null, 0, "RULE");
        }
        if (routingPolicy != null) return route(actor, text, matches, multi, linked);
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

    private IntentCandidate route(Actor actor, String text, List<IntentCatalog.Match> rules, boolean multi, Task linked) {
        var hardRule = rules.stream().filter(match -> !"NORMAL".equals(match.definition().risk())).findFirst();
        if (hardRule.isPresent()) return routed(hardRule.get().definition(), hardRule.get().score(),
                0.0, IntentRoutingPolicy.Band.RULE, "RULE", List.of(), false);

        var top = rules.isEmpty() ? null : rules.getFirst();
        boolean openTask = top != null && "AGENT".equals(top.definition().route());
        double score = multi || openTask || text.length() >= 48 ? .80
                : linked != null || rules.size() > 1 || text.length() >= 28 ? .59
                : top != null && text.length() < 9 ? .22 : .39;
        var band = routingPolicy.band(score);
        String strategy = band == IntentRoutingPolicy.Band.HYSTERESIS ? "HYBRID" : band.name();
        if (band == IntentRoutingPolicy.Band.HYSTERESIS && linked != null && linked.assessment() != null
                && routingPolicy.version().equals(linked.assessment().policyVersion())
                && comparisonKey(actor, linked.intent(), linked.id()).equals(linked.assessment().comparisonKey())) {
            strategy = linked.assessment().strategy();
        }
        if ("RULE".equals(strategy)) {
            Definition selected = top != null ? top.definition() : linked == null ? null : definition(linked.intent(), linked.definitionVersion());
            return routed(selected, top == null ? 0 : top.score(), score, band, strategy, List.of(), false);
        }

        var ranked = recall.rank(text, 5);
        var candidates = java.util.stream.Stream.concat(rules.stream().map(match -> match.definition().id()),
                ranked.stream().map(IntentRecall.Match::id)).distinct().limit(5).toList();
        var best = ranked.isEmpty() ? null : ranked.getFirst();
        double margin = best == null ? 0 : best.score() - (ranked.size() > 1 ? ranked.get(1).score() : 0);
        boolean reliable = best != null && best.score() >= routingPolicy.minimumConfidence() && margin >= routingPolicy.minimumMargin();
        boolean askModel = model.enabled() && ("LARGE_MODEL".equals(strategy) || "HYBRID".equals(strategy) && !reliable)
                && !candidates.isEmpty();
        if (askModel) {
            var selected = choose(text, candidates, .84);
            if (selected.definition() != null) return routed(selected.definition(), selected.confidence(), score, band, strategy, candidates, true);
            if (selected.outOfScope() && top == null) {
                return new IntentCandidate(null, 0, strategy,
                        new RoutingTrace(score, band.name(), routingPolicy.version(), candidates, true), true);
            }
        }
        Definition selected = top != null ? top.definition() : reliable ? catalog.get(best.id()) : null;
        double confidence = top != null ? top.score() : reliable ? best.score() : 0;
        return routed(selected, confidence, score, band, strategy, candidates, askModel);
    }

    private IntentCandidate routed(Definition definition, double confidence, double score, IntentRoutingPolicy.Band band,
                                   String strategy, List<String> candidates, boolean modelRequested) {
        return new IntentCandidate(definition, confidence, strategy,
                new RoutingTrace(score, band.name(), routingPolicy.version(), candidates, modelRequested));
    }

    private IntentCandidate choose(String text, List<String> allowed, double confidence) {
        try {
            // 任务中仅使用当前片段，模型请求不附带会话正文、用户身份或工具凭证。
            var choice = model.choose(text, allowed);
            if (choice.filter("out_of_scope"::equalsIgnoreCase).isPresent()) {
                return new IntentCandidate(null, 0, "LARGE_MODEL", null, true);
            }
            Definition selected = choice.filter(allowed::contains).map(catalog::get)
                    .filter(d -> "NORMAL".equals(d.risk())).orElse(null);
            return new IntentCandidate(selected, confidence, selected == null ? null : "LARGE_MODEL");
        } catch (RuntimeException ex) {
            return new IntentCandidate(null, confidence, null);
        }
    }

    private List<String> matchingFields(Task task, String text) {
        if ("ORDER.REFUND".equals(task.intent()) && task.workflowSkill() != null) return RefundIntake.matchingFields(text);
        Definition def = definition(task.intent(), task.definitionVersion());
        if (def == null) return List.of();
        var extracted = slots.extract(def, text, Map.of());
        return java.util.stream.Stream.concat(extracted.values().keySet().stream(), extracted.invalid().stream())
                .distinct().sorted().toList();
    }

    private Outcome apply(Actor actor, Request request, String text, Definition def, double confidence,
                          Session session, State before, Task target, List<Integer> dependencies, String strategy, boolean multi) {
        return apply(actor, request, text, def, confidence, session, before, target, dependencies, strategy, multi, null,
                target == null ? "NEW_TASK" : "CONTINUATION");
    }

    private Outcome apply(Actor actor, Request request, String text, Definition def, double confidence,
                          Session session, State before, Task target, List<Integer> dependencies, String strategy, boolean multi,
                          RoutingTrace routing, String relation) {
        if ("SYSTEM.IDENTITY".equals(def.id())) {
            return meta(def.id(), "NEW_TASK", null, "COMPLETED", "ASSISTANT_IDENTITY",
                    "我是企业智能助手，可以回答问题，并在权限范围内帮你查询业务信息、发起流程和执行任务。");
        }
        if ("SYSTEM.HELP".equals(def.id())) {
            return meta(def.id(), "NEW_TASK", null, "COMPLETED", "CAPABILITY_LIST",
                    "当前可识别的业务请求包括 " + String.join("、", catalog.capabilities()) + "。");
        }
        var detected = slots.extract(def, text, request.arguments() == null ? Map.of() : request.arguments());
        var merged = merger.merge(def, target, session.snapshot(), session.entities, session.now, text, detected);
        var values = merged.values();
        var provenance = merged.provenance();
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
        UUID id = target == null ? UUID.randomUUID() : target.id();
        var assessment = routing == null ? assess(actor, def, id, target, text, confidence, strategy, critical, multi)
                : routingAssessment(new IntentCandidate(def, confidence, strategy, routing), comparisonKey(actor, def.id(), id));
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
            if (slotState.complete() && "NORMAL".equals(def.risk())) ir = ir(def, id, values, provenance);
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
                "WAITING_CONFIRMATION".equals(status) ? confirmation(def.id(), values) : null, def.version(),
                target == null ? null : target.workflowSkill()));
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
        var resolved = context.control(session.context(), request.targetTaskId(), match, action);
        if (resolved.relation().needsDisambiguation()) {
            session.relations.put(0, resolved.relation());
            return meta("SYSTEM." + action, "AMBIGUOUS", null, "NEEDS_CLARIFICATION",
                    "TARGET_AMBIGUOUS", "上一轮有多个任务，请指定要操作的任务。");
        }
        Task target = resolved.task();
        if (target == null && "UNDO".equals(action) && has(text, "采购申请|请假申请|审批")
                && !has(text, "刚才|刚发起|上次")) return null;
        session.relations.put(0, resolved.relation());
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
        String comparison = comparisonKey(actor, def.id(), taskId);
        String applied = strategy == null ? (score < .30 ? "RULE" : score < .50 ? "RETRIEVAL_SMALL_MODEL" : "HYBRID") : strategy;
        if (critical) applied = "RULE";
        else if ("HYSTERESIS".equals(band) && target != null && target.assessment() != null
                && comparison.equals(target.assessment().comparisonKey())) applied = target.assessment().strategy();
        return new Assessment(score, confidence, band, applied, critical, comparison);
    }

    private static String comparisonKey(Actor actor, String intent, UUID task) {
        return fingerprint(intent + "|" + actor.agent() + "|" + actor.scopes().stream().sorted().toList()
                + "|" + actor.roles().stream().sorted().toList() + "|" + task);
    }

    private static Assessment routingAssessment(IntentCandidate candidate, String comparison) {
        RoutingTrace trace = candidate.routing();
        if (trace == null) return null;
        return new Assessment(trace.score(), candidate.confidence(), trace.band(), candidate.strategy(),
                candidate.definition() != null && "CRITICAL".equals(candidate.definition().risk()), comparison,
                trace.policyVersion(), trace.candidates(), trace.modelRequested());
    }

    private static Outcome blocked(String text) {
        return meta("SECURITY.BLOCKED", "NEW_TASK", null, "DENIED", text.length() > 4096 || has(text, "1万字|一万字|10000字")
                ? "INPUT_LENGTH" : "UNSAFE_INPUT", "该输入未通过安全校验。");
    }

    private static Outcome meta(String id, String relation, UUID task, String status, String reason, String question) {
        return meta(id, relation, task, status, reason, question, null);
    }

    private static Outcome meta(String id, String relation, UUID task, String status, String reason, String question, Assessment assessment) {
        return new Outcome(id, relation, task, status, "NONE", null, Map.of(), List.of(), List.of(), question, reason,
                List.of(), assessment == null ? new Assessment(.18, .95, "RULE", "RULE", "SECURITY.BLOCKED".equals(id), null) : assessment, null);
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
        private final Actor actor;
        private final List<Task> tasks;
        private final long revision, now;
        private final NormalizedInput input;
        private final ContextAssembler.Source source;
        private ContextSnapshot contextSnapshot;
        private boolean contextLoaded;
        private final Map<Integer, String> classifiedIntents = new LinkedHashMap<>();
        private final Map<Integer, ContextRelation> relations = new LinkedHashMap<>();
        private final Map<String, Object> entities;
        private final List<String> recent;
        private String summary;
        private long privacyRevision;
        private UUID focus;

        Session(Actor actor, NormalizedInput input, ContextSnapshot snapshot, ContextAssembler.Source source) {
            State before = snapshot.state();
            this.actor = actor;
            this.input = input;
            this.source = source;
            this.contextSnapshot = snapshot;
            this.tasks = new ArrayList<>(before.tasks());
            this.revision = before.revision() + 1;
            this.now = snapshot.capturedAt();
            this.entities = new LinkedHashMap<>(before.entities());
            this.recent = new ArrayList<>(before.recent());
            this.summary = before.summary();
            this.privacyRevision = before.privacyRevision();
            this.focus = before.focus();
        }

        ContextSnapshot context() {
            if (!contextLoaded) {
                contextSnapshot = assembler.attach(input, contextSnapshot, source);
                contextLoaded = true;
            }
            return contextSnapshot;
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
            var observation = assembler.observe(input, safeText.isEmpty() ? contextSnapshot : context());
            return new Analysis(List.copyOf(outcomes), snapshot(), responses.reply(outcomes), assembler.enrich(actor, observation, outcomes),
                    Map.copyOf(classifiedIntents), Map.copyOf(relations));
        }
    }
}
